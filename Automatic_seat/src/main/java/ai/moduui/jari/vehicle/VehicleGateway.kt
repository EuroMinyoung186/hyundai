package ai.moduui.jari.vehicle

import ai.moduui.core.model.SeatId
import ai.moduui.core.seat.SeatAxis
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class MoveDirection { FORWARD, BACKWARD, STOP }

/** 좌석 기능 하나의 지원 상태 (checkXxxCapabilities 결과) */
enum class Support(val labelKo: String) {
    READ_WRITE("읽기·제어"),
    READ_ONLY("읽기만"),
    UNSUPPORTED("미지원"),
    UNKNOWN("확인 전"),
}

enum class SeatFeature(val labelKo: String) {
    BACKREST_MOVE("등받이 이동"),
    BACKREST_POSITION("등받이 위치"),
    FORE_AFT_MOVE("앞뒤 이동"),
    FORE_AFT_POSITION("앞뒤 위치"),
    HEAT("열선"),
    VENT("통풍"),
    OCCUPANCY("착좌"),
}

/** SDK 호출 실패 이유. ErrorCode 이름을 그대로 담음 (ERROR_SECURITY_PERMISSION 등) */
class VehicleCallException(val code: String, message: String? = null) : Exception(message ?: code)

data class VehicleStatus(
    val connected: Boolean = false,
    val simulated: Boolean = false,
    val support: Map<SeatId, Map<SeatFeature, Support>> = emptyMap(),
    /** true = 사람이 앉아 있음, null = 모름 */
    val occupancy: Map<SeatId, Boolean?> = emptyMap(),
    /** 열린 문이 있는 좌석 쪽 */
    val openDoors: Set<SeatId> = emptySet(),
    val lastError: String? = null,
    /** Seat 클래스에서 찾은 앞뒤 이동 메서드 이름 (리플렉션 진단용) */
    val foreAftMethods: List<String> = emptyList(),
    /** 좌석 위치 0..100 (시뮬레이터만 계속 알려 줌. 실차는 비어 있고 3D 화면이 목표값으로 보여 줌) */
    val positions: Map<Pair<SeatId, SeatAxis>, Int> = emptyMap(),
) {
    fun support(seat: SeatId, feature: SeatFeature): Support = support[seat]?.get(feature) ?: Support.UNKNOWN
}

sealed interface VehicleEvent {
    data class DoorChanged(val seat: SeatId, val open: Boolean) : VehicleEvent
    data class OccupancyChanged(val seat: SeatId, val occupied: Boolean?) : VehicleEvent
    data class DoorLockChanged(val locked: Boolean) : VehicleEvent
}

/**
 * Pleos Vehicle SDK 를 감싼 좌석 게이트웨이. 실제 구현(Pleos)과 시뮬레이션(Fake)이 같은 인터페이스를 씀.
 * 모든 함수는 suspend 이고 SDK 비동기 콜백(onComplete/onFailed)을 코루틴으로 바꿔 줌.
 */
interface VehicleGateway {
    val status: StateFlow<VehicleStatus>
    val events: SharedFlow<VehicleEvent>

    fun start()
    fun stop()

    /** 좌석별 지원 여부 다시 확인 */
    suspend fun refreshCapabilities()

    suspend fun positionRange(seat: SeatId, axis: SeatAxis): IntRange?
    suspend fun readPosition(seat: SeatId, axis: SeatAxis): Int?
    suspend fun move(seat: SeatId, axis: SeatAxis, direction: MoveDirection)

    /**
     * 에뮬레이터 시연 전용: SDK 가 거부한 주행 중 소폭 조절을 콘솔 값으로 표현함. 실제 좌석 명령이 아님.
     * 실차·출시 빌드에는 이 경로가 없어 false 를 돌려줌.
     */
    suspend fun demoNudge(seat: SeatId, axis: SeatAxis, deltaPercent: Int): Boolean = false

    suspend fun readHeat(seat: SeatId): Int?
    suspend fun setHeat(seat: SeatId, level: Int)
    suspend fun readVent(seat: SeatId): Int?
    suspend fun setVent(seat: SeatId, level: Int)

    /** 실내 온도(앞줄 좌우 같이)를 delta 도 바꿈. 바꾼 뒤 설정 온도, 못 하면 null */
    suspend fun nudgeCabinTemperature(deltaC: Float): Float? = null
}
