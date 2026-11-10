package ai.moduui.jari.vehicle

import ai.moduui.core.model.SeatId
import ai.moduui.core.seat.SeatAxis
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

/**
 * Pleos 차량 서비스가 없는 환경(일반 안드로이드 에뮬레이터, 단위 테스트)용 시뮬레이터.
 * 좌석 위치가 실제처럼 천천히 움직이고, 착좌·도어를 개발자 콘솔이나 adb 로 바꿀 수 있음.
 * 화면에는 "시뮬레이션" 배지가 붙음.
 */
class FakeVehicleGateway(
    private val unitsPerSecond: Int = 40,
) : VehicleGateway {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow(VehicleStatus(simulated = true))
    override val status: StateFlow<VehicleStatus> = _status
    private val _events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<VehicleEvent> = _events

    /** 위치 0..100 (AAOS 규약: 값이 클수록 앞쪽 / 곧게 세움) */
    private val positions = mutableMapOf<Pair<SeatId, SeatAxis>, Int>()
    private val heat = mutableMapOf<SeatId, Int>()
    private val vent = mutableMapOf<SeatId, Int>()
    private val motion = mutableMapOf<Pair<SeatId, SeatAxis>, Job>()

    /** 테스트에서 특정 기능을 "미지원"으로 만들 때 */
    var unsupported: MutableSet<Pair<SeatId, SeatFeature>> = mutableSetOf()

    /** 기록: "move R2_RIGHT FORE_AFT BACKWARD" 처럼 */
    val commandLog = mutableListOf<String>()

    init {
        SeatId.entries.forEach { id ->
            positions[id to SeatAxis.FORE_AFT] = 50
            positions[id to SeatAxis.BACKREST] = 60
            heat[id] = 0
            vent[id] = 0
        }
        heat[SeatId.R2_RIGHT] = 2 // 열선이 켜진 채로 시작해 "카시트 아래 열선 끔"이 보이게
    }

    override fun start() {
        _status.update {
            it.copy(
                connected = true,
                occupancy = mapOf(SeatId.R1_RIGHT to false, SeatId.R2_LEFT to false, SeatId.R2_RIGHT to false, SeatId.R3_LEFT to false, SeatId.R3_RIGHT to false),
                foreAftMethods = listOf("(시뮬레이션)"),
            )
        }
        publishPositions()
        scope.launch { refreshCapabilities() }
    }

    override fun stop() {
        motion.values.forEach { it.cancel() }
        _status.update { it.copy(connected = false) }
    }

    override suspend fun refreshCapabilities() {
        val map = SeatId.entries.associateWith { id ->
            SeatFeature.entries.associateWith { f ->
                when {
                    id to f in unsupported -> Support.UNSUPPORTED
                    id.isDriver && f == SeatFeature.OCCUPANCY -> Support.UNSUPPORTED
                    else -> Support.READ_WRITE
                }
            }
        }
        _status.update { it.copy(support = map) }
    }

    private fun check(seat: SeatId, feature: SeatFeature) {
        if (seat to feature in unsupported) throw VehicleCallException("ERROR_NOT_SUPPORTED_AREA")
    }

    override suspend fun positionRange(seat: SeatId, axis: SeatAxis): IntRange? {
        check(seat, if (axis == SeatAxis.BACKREST) SeatFeature.BACKREST_POSITION else SeatFeature.FORE_AFT_POSITION)
        return 0..100
    }

    override suspend fun readPosition(seat: SeatId, axis: SeatAxis): Int? {
        check(seat, if (axis == SeatAxis.BACKREST) SeatFeature.BACKREST_POSITION else SeatFeature.FORE_AFT_POSITION)
        delay(20)
        return synchronized(positions) { positions[seat to axis] }
    }

    override suspend fun move(seat: SeatId, axis: SeatAxis, direction: MoveDirection) {
        check(seat, if (axis == SeatAxis.BACKREST) SeatFeature.BACKREST_MOVE else SeatFeature.FORE_AFT_MOVE)
        commandLog += "move $seat $axis $direction"
        val key = seat to axis
        motion.remove(key)?.cancel()
        if (direction == MoveDirection.STOP) return
        val step = if (direction == MoveDirection.FORWARD) 1 else -1
        motion[key] = scope.launch {
            val periodMs = (1000L / unitsPerSecond).coerceAtLeast(5)
            while (isActive) {
                val reachedEnd = synchronized(positions) {
                    val next = (positions[key]!! + step).coerceIn(0, 100)
                    val end = next == positions[key]
                    positions[key] = next
                    end
                }
                publishPositions()
                if (reachedEnd) break
                delay(periodMs)
            }
        }
    }

    override suspend fun readHeat(seat: SeatId): Int? = heat[seat]
    override suspend fun setHeat(seat: SeatId, level: Int) {
        check(seat, SeatFeature.HEAT)
        commandLog += "heat $seat $level"
        heat[seat] = level
    }

    override suspend fun readVent(seat: SeatId): Int? = vent[seat]
    override suspend fun setVent(seat: SeatId, level: Int) {
        check(seat, SeatFeature.VENT)
        commandLog += "vent $seat $level"
        vent[seat] = level
    }

    // ---- 개발자 콘솔 / adb 에서 쓰는 조작 ----

    fun setOccupied(seat: SeatId, occupied: Boolean?) {
        _status.update { it.copy(occupancy = it.occupancy + (seat to occupied)) }
        _events.tryEmit(VehicleEvent.OccupancyChanged(seat, occupied))
    }

    fun setDoorOpen(seat: SeatId, open: Boolean) {
        _status.update { st -> st.copy(openDoors = if (open) st.openDoors + seat else st.openDoors - seat) }
        _events.tryEmit(VehicleEvent.DoorChanged(seat, open))
    }

    /** 3D 화면이 좌석이 실제로 움직이는 모습을 그릴 수 있게 위치를 상태에 실음 */
    private fun publishPositions() {
        val snap = synchronized(positions) { positions.toMap() }
        _status.update { it.copy(positions = snap) }
    }

    fun snapshot(): Map<String, Int> = synchronized(positions) {
        positions.mapKeys { (k, _) -> "${k.first}.${k.second}" } +
            heat.mapKeys { "${it.key}.HEAT" } + vent.mapKeys { "${it.key}.VENT" }
    }
}
