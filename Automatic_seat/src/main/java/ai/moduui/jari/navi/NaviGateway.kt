package ai.moduui.jari.navi

import ai.moduui.core.model.GeoPoint
import ai.moduui.core.model.RouteSnapshot
import ai.moduui.core.reststop.RestArea
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class NaviState(
    val connected: Boolean = false,
    val simulated: Boolean = false,
    /** NavigationStatus 이름 (INACTIVE, FREE_DRIVE, ROUTE_DRIVE ...) */
    val navigationStatus: String? = null,
    val guiding: Boolean = false,
    val route: RouteSnapshot? = null,
    val current: GeoPoint? = null,
    val waypoints: List<String> = emptyList(),
    val lastError: String? = null,
)

sealed interface NaviEvent {
    data class DestinationArrived(val name: String?) : NaviEvent
    data class WaypointArrived(val name: String?) : NaviEvent
    data object WaypointChanged : NaviEvent
    data object RouteStarted : NaviEvent
    data class Error(val code: String) : NaviEvent
}

/**
 * Pleos NaviHelper 래퍼. 기본 내비 앱에 요청을 보내고 결과는 리스너로 받음.
 * NaviHelper 는 조회 API(getXxxInfo)를 불러야 콜백이 오므로 경로 안내 중에만 낮은 주기로 조회함.
 */
interface NaviGateway {
    val state: StateFlow<NaviState>
    val events: SharedFlow<NaviEvent>

    fun start()
    fun stop()

    /** 목적지·남은 시간·현재 위치·경로 상태 조회 요청 (결과는 state 로 들어옴) */
    fun refresh()

    /** 현재 위치를 한 번 받아 옴. timeout 안에 못 받으면 마지막 값 */
    suspend fun currentLocation(timeoutMs: Long = 1_500): GeoPoint?

    /** 경유지 추가 (NaviHelper 는 최대 2개). 성공 여부는 waypoint 이벤트로 확인. */
    suspend fun addWaypoint(restArea: RestArea, first: Boolean = true): Boolean

    /** 기본 내비에 목적지 경로 안내 요청 (NaviHelper.requestRoute). 결과는 routeStarted/destination 콜백으로 들어옴. */
    fun requestRoute(name: String, to: GeoPoint): Boolean

    /** 경로 안내 종료 (NaviHelper.cancelRoute) */
    fun cancelRoute()
}
