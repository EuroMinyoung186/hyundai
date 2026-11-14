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
    /** 내비의 즐겨찾기 + 최근 목적지 (목적지 고르기용). 즐겨찾기가 먼저 */
    val places: List<NaviPlace> = emptyList(),
    /** 다음 회전 안내 (onTBTInfo 의 첫 항목) */
    val nextTurn: NaviTurn? = null,
    /** 지금 도로 제한 속도 km/h (onCurrentRoadSpeedLimit, 0 이하는 모름) */
    val speedLimit: Int? = null,
    /** 앞쪽 주의 지점 (onCautionPointInfo). 가까운 순 */
    val cautions: List<NaviCaution> = emptyList(),
    /** 앞쪽 단속 카메라 중 가장 가까운 것 (onCameraAlert) */
    val camera: NaviCamera? = null,
    /** 경유지 · 목적지까지 남은 거리 · 시간 (onDrivingInfo). 경유지 순서대로, 목적지가 마지막 */
    val legs: List<NaviLeg> = emptyList(),
)

/** 다음 회전. type 은 Pleos TurnType 이름 (TURN_LEFT, TURN_RESTAREA ...) */
data class NaviTurn(val type: String, val meters: Int, val description: String)

/** 주의 지점. type 은 Pleos CautionPointType 이름 (SPEED_BUMP, SCHOOL_ZONE ...) */
data class NaviCaution(val type: String, val meters: Int)

/** 단속 카메라. type 은 Pleos CameraType 이름 */
data class NaviCamera(val type: String, val speedLimit: Int, val meters: Int, val overSpeed: Boolean)

/** 경유지 또는 목적지까지 */
data class NaviLeg(val name: String, val meters: Int, val seconds: Int, val waypoint: Boolean)

/** 내비에서 받은 장소 하나 (즐겨찾기 · 최근 목적지) */
data class NaviPlace(val name: String, val address: String, val point: GeoPoint, val bookmark: Boolean)

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

    /** 즐겨찾기 · 최근 목적지 다시 받기 (결과는 state.places 로) */
    fun loadPlaces()

    /** 내비의 장소 검색 화면을 검색어와 함께 엶 (NaviHelper.requestNavigationScreen(SearchPlace)). 고른 곳은 목적지 콜백으로 들어옴 */
    fun searchPlace(keyword: String): Boolean
}
