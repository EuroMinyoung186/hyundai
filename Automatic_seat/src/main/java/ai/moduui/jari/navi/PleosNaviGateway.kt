package ai.moduui.jari.navi

import ai.moduui.core.model.GeoPoint
import ai.moduui.core.model.RouteSnapshot
import ai.moduui.core.reststop.RestArea
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime

/**
 * Pleos NaviHelper 3.0.5 연동.
 *
 * 사용 API: initialize / addListener / getDestinationInfo / getCurrentLocationInfo / getRouteStateInfo /
 *          getWaypointInfo / addWaypoint / removeListener / release
 * 권한: pleos.car.permission.NAVI_ROUTE (get*Info), NAVI_ROUTE_SEARCH (addWaypoint)
 *
 * 공개 측정 기록상 조회 요청 없이는 위치·목적지 콜백이 오지 않음 → 경로 안내 중에만 30초마다 조회.
 * (5초보다 길게 잡아 정책의 "불필요한 폴링 최소화"를 지킴. 화면이 아기 상태를 볼 때만 필요한 정밀도임)
 */
class PleosNaviGateway(
    context: Context,
    private val clock: () -> LocalDateTime,
    private val pollMillis: Long = 30_000,
) : NaviGateway {

    private val appContext = context.applicationContext
    private var helper: NaviHelper? = null
    private var scope = newScope()
    private var pollJob: Job? = null
    private val _state = MutableStateFlow(NaviState())
    override val state: StateFlow<NaviState> = _state
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<NaviEvent> = _events

    @Volatile private var locationWaiter: CompletableDeferred<GeoPoint>? = null
    @Volatile private var waypointWaiter: CompletableDeferred<Boolean>? = null

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val p = GeoPoint(currentLocationInfo.latitude, currentLocationInfo.longitude)
            _state.update { it.copy(current = p) }
            locationWaiter?.complete(p)
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val remain = destinationInfo.remainTime.toLong()
            val route = if (remain > 0) {
                RouteSnapshot(
                    destinationName = destinationInfo.name,
                    destination = GeoPoint(destinationInfo.latitude, destinationInfo.longitude),
                    remainSeconds = remain,
                    remainMeters = destinationInfo.remainDistance.toLong().takeIf { it > 0 },
                    receivedAt = clock(),
                )
            } else null
            _state.update { it.copy(route = route, guiding = route != null || it.guiding) }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            // 에뮬레이터는 안내 중에도 isRouting=false 를 줄 수 있다는 보고가 있어 naviMode 로 판단
            val mode = "${routeStateInfo.naviMode}"
            val guiding = mode == "ROUTE_GUIDING" || mode == "SIMULATION_GUIDING"
            _state.update { it.copy(guiding = guiding) }
            if (guiding) startPolling() else stopPolling()
        }

        override fun onNavigationStatus(status: NavigationStatus) {
            val name = "$status"
            _state.update { it.copy(navigationStatus = name) }
            if (name == "ROUTE_DRIVE") startPolling()
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            _events.tryEmit(NaviEvent.RouteStarted)
        }

        override fun onRoutingCompleted() {
            refresh()
        }

        override fun onDestinationChanged() {
            refresh()
        }

        override fun onRouteCancelled() {
            _state.update { it.copy(route = null, guiding = false) }
            stopPolling()
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            _state.update { it.copy(route = null, guiding = false) }
            stopPolling()
            _events.tryEmit(NaviEvent.DestinationArrived(destinationArrivedInfo.name))
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            _events.tryEmit(NaviEvent.WaypointArrived(waypointArrivedInfo.name))
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            waypointWaiter?.complete(true)
            _events.tryEmit(NaviEvent.WaypointChanged)
            runCatching { helper?.getWaypointInfo() }
            runCatching { helper?.getDestinationInfo() }
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            _state.update { it.copy(waypoints = waypointInfoList.map { w -> w.name ?: "" }) }
        }

        override fun onError(errorCode: NaviErrorCode) {
            val code = "$errorCode"
            Log.w(TAG, "onError $code")
            waypointWaiter?.complete(false)
            _state.update { it.copy(lastError = code) }
            _events.tryEmit(NaviEvent.Error(code))
        }
    }

    override fun start() {
        if (helper != null) return
        scope = newScope()
        var h: NaviHelper? = null
        try {
            h = NaviHelper(appContext)
            h.initialize()
            h.addListener(listener)
            helper = h
            _state.update { it.copy(connected = true, lastError = null) }
            refresh()
        } catch (e: Exception) {
            Log.e(TAG, "NaviHelper initialize failed", e)
            runCatching { h?.release() }
            helper = null
            _state.update { it.copy(connected = false, lastError = "initialize: ${e.javaClass.simpleName}") }
        }
    }

    override fun stop() {
        stopPolling()
        val h = helper ?: return
        runCatching { h.removeListener(listener) }
        runCatching { h.release() }
        helper = null
        scope.cancel()
        _state.update { it.copy(connected = false) }
    }

    override fun refresh() {
        val h = helper ?: return
        scope.launch {
            runCatching { h.getRouteStateInfo() }
            runCatching { h.getDestinationInfo() }
            runCatching { h.getCurrentLocationInfo() }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(pollMillis)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    override suspend fun currentLocation(timeoutMs: Long): GeoPoint? {
        val h = helper ?: return _state.value.current
        val waiter = CompletableDeferred<GeoPoint>()
        locationWaiter = waiter
        withContext(Dispatchers.Main) { runCatching { h.getCurrentLocationInfo() } }
        return withTimeoutOrNull(timeoutMs) { waiter.await() } ?: _state.value.current
    }

    override suspend fun addWaypoint(restArea: RestArea, first: Boolean): Boolean {
        val h = helper ?: return false
        val waiter = CompletableDeferred<Boolean>()
        waypointWaiter = waiter
        val request = RequestWaypointInfo(
            latitude = restArea.location.lat,
            longitude = restArea.location.lng,
            waypointIndex = if (first) WaypointIndex.FIRST else WaypointIndex.SECOND,
            poiId = "",
            poiName = restArea.name,
            poiSubId = "0",
            address = restArea.address,
        )
        val sent = withContext(Dispatchers.Main) { runCatching { h.addWaypoint(request) }.isSuccess }
        if (!sent) return false
        // 경유지 변경 이벤트가 안 와도 요청은 전달된 것이므로 시간 초과는 성공으로 봄 (에뮬레이터 보고 반영)
        return withTimeoutOrNull(2_500) { waiter.await() } ?: true
    }

    override fun requestRoute(name: String, to: GeoPoint): Boolean {
        val h = helper ?: return false
        val request = RouteInfo(
            to.lat, to.lng,
            null, null,
            name,
            "",
            "",
            "0",
            RouteOption.RECOMMENDED,
            "",
            false,
        )
        return runCatching { h.requestRoute(request) }
            .onFailure { Log.w(TAG, "requestRoute failed", it) }
            .isSuccess
    }

    override fun cancelRoute() {
        runCatching { helper?.cancelRoute() }.onFailure { Log.w(TAG, "cancelRoute failed", it) }
    }

    companion object {
        private const val TAG = "PleosNavi"
    }
}
