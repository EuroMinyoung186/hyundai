package ai.moduui.jari.navi

import ai.moduui.core.model.GeoPoint
import ai.moduui.core.model.RouteSnapshot
import ai.moduui.core.reststop.RestArea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

/**
 * 내비가 없는 환경용 경로 시뮬레이터. 서울 → 강릉(약 3시간)을 기본 경로로,
 * 주행 중일 때만 시뮬레이션 시계에 맞춰 남은 시간이 줄고 위치가 경로를 따라 움직임.
 */
class FakeNaviGateway(
    private val clock: () -> LocalDateTime,
    private val isDriving: () -> Boolean,
) : NaviGateway {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(NaviState(simulated = true))
    override val state: StateFlow<NaviState> = _state
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<NaviEvent> = _events

    private var job: Job? = null
    private var origin = SEOUL
    private var destination = GANGNEUNG
    private var destinationName = "강릉"
    private var totalSeconds = 180 * 60L
    private var drivenSeconds = 0L
    private var lastTick: LocalDateTime? = null
    private val waypoints = mutableListOf<String>()

    override fun start() {
        _state.update { it.copy(connected = true, current = origin) }
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                tick()
                delay(1_000)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        _state.update { it.copy(connected = false) }
    }

    fun startRoute(name: String = "강릉", to: GeoPoint = GANGNEUNG, minutes: Int = 180, from: GeoPoint = SEOUL) {
        destinationName = name
        destination = to
        origin = from
        totalSeconds = minutes * 60L
        drivenSeconds = 0
        lastTick = clock()
        waypoints.clear()
        _state.update { it.copy(guiding = true, navigationStatus = "ROUTE_DRIVE", waypoints = emptyList()) }
        publish()
        _events.tryEmit(NaviEvent.RouteStarted)
    }

    /** 개발자 콘솔: 남은 시간을 바로 바꿔 보기 (예: 도착 4분 전) */
    fun jumpToRemaining(minutes: Int) {
        drivenSeconds = (totalSeconds - minutes * 60L).coerceIn(0, totalSeconds)
        publish()
    }

    override fun cancelRoute() {
        _state.update { it.copy(route = null, guiding = false, navigationStatus = "FREE_DRIVE") }
    }

    private fun tick() {
        val now = clock()
        val prev = lastTick
        lastTick = now
        if (!_state.value.guiding || prev == null) return
        if (isDriving()) {
            drivenSeconds += Duration.between(prev, now).seconds.coerceAtLeast(0)
        }
        if (drivenSeconds >= totalSeconds) {
            _state.update { it.copy(route = null, guiding = false, navigationStatus = "FREE_DRIVE", current = destination) }
            _events.tryEmit(NaviEvent.DestinationArrived(destinationName))
            return
        }
        publish()
    }

    private fun publish() {
        val f = (drivenSeconds.toDouble() / totalSeconds).coerceIn(0.0, 1.0)
        val here = GeoPoint(origin.lat + (destination.lat - origin.lat) * f, origin.lng + (destination.lng - origin.lng) * f)
        _state.update {
            it.copy(
                current = here,
                route = RouteSnapshot(destinationName, destination, totalSeconds - drivenSeconds, null, clock()),
            )
        }
    }

    override fun refresh() = publish()

    override suspend fun currentLocation(timeoutMs: Long): GeoPoint? = _state.value.current

    override suspend fun addWaypoint(restArea: RestArea, first: Boolean): Boolean {
        if (!_state.value.guiding) return false
        if (waypoints.size >= 2) return false
        waypoints += restArea.name
        _state.update { it.copy(waypoints = waypoints.toList()) }
        _events.tryEmit(NaviEvent.WaypointChanged)
        return true
    }

    override fun requestRoute(name: String, to: GeoPoint): Boolean {
        startRoute(name = name, to = to)
        return true
    }

    companion object {
        val SEOUL = GeoPoint(37.2700, 127.1000)
        val GANGNEUNG = GeoPoint(37.7519, 128.8761)
    }
}
