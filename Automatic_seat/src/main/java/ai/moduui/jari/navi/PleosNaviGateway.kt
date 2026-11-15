package ai.moduui.jari.navi

import ai.moduui.core.model.GeoPoint
import ai.moduui.core.model.RouteSnapshot
import ai.moduui.core.reststop.RestArea
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.RouteOverviewShowInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RecentDestinationInfo
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
 *          getWaypointInfo / addWaypoint / removeListener / release /
 *          getBookmarkInfo · getRecentDestinationInfo · requestNavigationScreen(SearchPlace) (목적지 고르기)
 * 내비가 알아서 보내는 알림(Notify): onTBTInfo · onCurrentRoadSpeedLimit · onCautionPointInfo · onCameraAlert · onDrivingInfo
 *   → 아기가 잘 때 과속방지턱 · 급회전 미리 알림, 수유 휴게소(경유지)까지 남은 거리 (RoadAhead.kt)
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
            _state.update { it.copy(route = null, guiding = false, nextTurn = null, cautions = emptyList(), camera = null, legs = emptyList()) }
            stopPolling()
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            _state.update { it.copy(route = null, guiding = false, nextTurn = null, cautions = emptyList(), camera = null, legs = emptyList()) }
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

        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
            bookmarks = bookmarkInfoList.mapNotNull { b ->
                b.name?.takeIf { it.isNotBlank() }?.let { NaviPlace(it, b.address.orEmpty(), GeoPoint(b.latitude, b.longitude), bookmark = true) }
            }
            publishPlaces()
        }

        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
            recents = recentDestinationInfoList.mapNotNull { r ->
                r.name?.takeIf { it.isNotBlank() }?.let { NaviPlace(it, r.address.orEmpty(), GeoPoint(r.latitude, r.longitude), bookmark = false) }
            }
            publishPlaces()
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val t = tbtInfoList.firstOrNull()
            _state.update { it.copy(nextTurn = t?.let { NaviTurn("${it.type}", it.distance.toInt(), it.description) }) }
        }

        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            _state.update { it.copy(speedLimit = speedLimit.takeIf { v -> v > 0 }) }
        }

        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            _state.update { it.copy(cautions = cautionPointList.map { c -> NaviCaution("${c.type}", c.remainDistance) }.sortedBy { c -> c.meters }) }
        }

        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {
            val c = cameraAlertList.filter { it.status }.minByOrNull { it.remainDistance }
            _state.update { it.copy(camera = c?.let { NaviCamera("${it.type}", it.speedLimit, it.remainDistance, it.isOverSpeed || it.isOverSectionAvrSpeed) }) }
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            val legs = drivingInfo.waypoints.map { NaviLeg(it.name, it.distance, it.duration, waypoint = true) } +
                drivingInfo.destination.let { NaviLeg(it.name, it.distance, it.duration, waypoint = false) }
            _state.update { it.copy(legs = legs) }
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
            // 회전 안내는 주행하며 알림으로도 오지만, 안내 중이면 한 번 더 물어 봄 (에뮬레이터는 차가 안 움직여 알림이 안 옴)
            if (_state.value.guiding) runCatching { h.getTBTInfo() }
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

    override fun removeWaypoints() {
        runCatching { helper?.removeWaypoint(WaypointIndex.ALL) }.onFailure { Log.w(TAG, "removeWaypoint failed", it) }
    }

    @Volatile private var bookmarks: List<NaviPlace> = emptyList()
    @Volatile private var recents: List<NaviPlace> = emptyList()

    /** 즐겨찾기 먼저, 같은 이름은 한 번만 */
    private fun publishPlaces() {
        _state.update { it.copy(places = (bookmarks + recents).distinctBy { p -> p.name }.take(8)) }
    }

    override fun loadPlaces() {
        val h = helper ?: return
        scope.launch {
            runCatching { h.getBookmarkInfo() }.onFailure { Log.w(TAG, "getBookmarkInfo failed", it) }
            runCatching { h.getRecentDestinationInfo() }.onFailure { Log.w(TAG, "getRecentDestinationInfo failed", it) }
        }
    }

    override fun searchPlace(keyword: String): Boolean {
        val h = helper ?: return false
        return runCatching { h.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword)) }
            .onFailure { Log.w(TAG, "searchPlace failed", it) }
            .isSuccess
    }

    override fun bringToFront(): Boolean {
        val h = helper ?: return false
        return runCatching { h.requestNavigationScreen(NavigationScreenRequest.Foreground) }
            .onFailure { Log.w(TAG, "bringToFront failed", it) }
            .isSuccess
    }

    override fun showRouteOverview(durationMs: Int): Boolean {
        val h = helper ?: return false
        return runCatching { h.requestNavigationScreen(NavigationScreenRequest.OverviewRoute(RouteOverviewShowInfo(durationMs))) }
            .onFailure { Log.w(TAG, "showRouteOverview failed", it) }
            .isSuccess
    }

    companion object {
        private const val TAG = "PleosNavi"
    }
}
