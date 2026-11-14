package ai.moduui.jari.vehicle

import ai.moduui.core.model.SeatId
import ai.moduui.core.seat.SeatAxis
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pleos Vehicle SDK 2.3.2 좌석·도어 연동.
 *
 * 사용하는 API (공식 샘플·SDK 시그니처 기준)
 *  - 조회: checkSeatBackrestAnglePositionCapabilities, getCurrentSeatBackrestAnglePosition,
 *          checkSeatTemperatureCapabilities, getHvacSeatTemperature, checkSeatVentilationCapabilities,
 *          getHvacSeatVentilationState, checkSeatOccupancyCapabilities, registerSeatOccupancy, Door.registerDoorPosition
 *  - 제어(Vehicle Control, 콘솔 기준 Coming Soon): moveSeatBackrestAngle, setHvacSeatTemperature, setHvacSeatVentilation
 *  - 앞뒤 이동은 공개 예제가 없어 Seat 클래스에서 이름에 "ForeAft" 가 들어간 메서드를 리플렉션으로 찾음 (SeatForeAftReflection)
 *
 * 권한: pleos.car.permission.CAR_SEATS / CONTROL_CAR_SEATS / CAR_DOORS
 */
class PleosVehicleGateway(
    context: Context,
    /** 에뮬레이터 2.1.0 에서 착좌 Boolean 이 반대로 온다는 실측 보고가 있어 설정으로 둠 */
    private val occupancyTrueMeansVacant: () -> Boolean,
) : VehicleGateway {

    private val appContext = context.applicationContext
    private var vehicle: Vehicle? = null
    private var scope = newScope()
    private val _status = MutableStateFlow(VehicleStatus(simulated = false))
    override val status: StateFlow<VehicleStatus> = _status
    private val _events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<VehicleEvent> = _events

    private val unregister = mutableListOf<() -> Unit>()
    private var foreAft: SeatForeAftReflection? = null
    private val console = ConsoleSeatPositions.ifAvailable()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun start() {
        if (vehicle != null) return
        scope = newScope()
        val v = Vehicle(appContext)
        try {
            v.initialize()
        } catch (e: Exception) {
            Log.e(TAG, "Vehicle.initialize failed", e)
            _status.update { it.copy(connected = false, lastError = "initialize: ${e.toCode()}") }
            return
        }
        vehicle = v
        foreAft = SeatForeAftReflection(v.getSeat())
        _status.update { it.copy(connected = true, lastError = null, foreAftMethods = foreAft?.found.orEmpty()) }
        scope.launch {
            refreshCapabilities()
            registerListeners(v)
        }
        // 좌석 위치: 에뮬레이터는 콘솔 값(SDK 읽기가 안 바뀜), 실차는 SDK getCurrentSeat*Position
        if (console != null) scope.launch { watchConsolePositions(console) } else scope.launch { watchSdkPositions() }
    }

    /** 실차: SDK 로 좌석 위치를 읽어 0..100 으로 맞춰 둠 (휴대폰 3D 가 그대로 그림) */
    private suspend fun watchSdkPositions() {
        while (true) {
            val now = mutableMapOf<Pair<SeatId, SeatAxis>, Int>()
            for (seat in SeatId.entries) for (axis in SeatAxis.entries) {
                val feature = if (axis == SeatAxis.FORE_AFT) SeatFeature.FORE_AFT_POSITION else SeatFeature.BACKREST_POSITION
                val sup = _status.value.support(seat, feature)
                if (sup != Support.READ_WRITE && sup != Support.READ_ONLY) continue
                val raw = runCatching { sdkPosition(seat, axis) }.getOrNull() ?: continue
                val range = positionRange(seat, axis) ?: continue
                val span = (range.last - range.first).coerceAtLeast(1)
                now[seat to axis] = ((raw - range.first) * 100 / span).coerceIn(0, 100)
            }
            if (now.isNotEmpty()) _status.update { it.copy(positions = now) }
            delay(SDK_POLL_MS)
        }
    }

    /** 3D 화면이 시연 중인 좌석 위치를 그대로 보여 주도록 주기적으로 읽음 */
    private suspend fun watchConsolePositions(c: ConsoleSeatPositions) {
        var compared = false
        var tick = 0
        while (true) {
            if (tick++ % 5 == 0) c.keepAlive() // 2초마다 (콘솔은 약 3초 동안 세션이 없으면 비활성)
            c.readAll()?.let { now ->
                _status.update { it.copy(positions = now) }
                if (!compared) {
                    compared = true
                    val key = SeatId.R2_RIGHT to SeatAxis.FORE_AFT
                    val sdk = runCatching { sdkPosition(key.first, key.second) }.getOrNull()
                    Log.i(TAG, "seat position ${key.first} ${key.second}: sdk=$sdk console=${now[key]}")
                }
            }
            delay(CONSOLE_POLL_MS)
        }
    }

    override fun stop() {
        unregister.forEach { runCatching(it) }
        unregister.clear()
        scope.cancel()
        // 콜백이 끝나기 전에 release 하지 않도록 리스너 해제 후 마지막에 release
        runCatching { vehicle?.release() }
        vehicle = null
        _status.update { it.copy(connected = false) }
    }

    private fun seatApi(): Seat = vehicle?.getSeat() ?: throw VehicleCallException("NOT_CONNECTED")

    override suspend fun refreshCapabilities() {
        val s = runCatching { seatApi() }.getOrNull() ?: return
        val result = SeatId.entries.associateWith { mutableMapOf<SeatFeature, Support>() }

        fun mark(feature: SeatFeature, cfg: CapabilityConfig, seats: Collection<SeatId>) {
            val sup = cfg.toSupport()
            SeatId.entries.forEach { id -> result[id]!![feature] = if (id in seats) sup else Support.UNSUPPORTED }
        }

        val failed = mutableSetOf<SeatFeature>()

        suspend fun tryCheck(feature: SeatFeature, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "capability $feature: ${e.toCode()}")
                failed += feature
                SeatId.entries.forEach { id -> result[id]!![feature] = Support.UNSUPPORTED }
            }
        }

        tryCheck(SeatFeature.BACKREST_MOVE) {
            val (cfg, areas) = call<Pair<CapabilityConfig, List<SeatArea>>> { ok, err ->
                s.checkMoveSeatBackrestAngleCapabilities(onComplete = { c, a -> ok(c to a) }, onFailed = err)
            }
            mark(SeatFeature.BACKREST_MOVE, cfg, areas.toSeatIds())
        }
        tryCheck(SeatFeature.BACKREST_POSITION) {
            val (cfg, bounds) = call<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, err ->
                s.checkSeatBackrestAnglePositionCapabilities(onComplete = { c, b -> ok(c to b) }, onFailed = err)
            }
            mark(SeatFeature.BACKREST_POSITION, cfg, bounds.keys.toSeatIds())
            backrestBounds = bounds
        }
        tryCheck(SeatFeature.HEAT) {
            val (cfg, bounds) = call<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, err ->
                s.checkSeatTemperatureCapabilities(onComplete = { c, b -> ok(c to b) }, onFailed = err)
            }
            mark(SeatFeature.HEAT, cfg, bounds.keys.toSeatIds())
        }
        tryCheck(SeatFeature.VENT) {
            val (cfg, bounds) = call<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Int>>>> { ok, err ->
                s.checkSeatVentilationCapabilities(onComplete = { c, b -> ok(c to b) }, onFailed = err)
            }
            mark(SeatFeature.VENT, cfg, bounds.keys.toSeatIds())
        }
        tryCheck(SeatFeature.OCCUPANCY) {
            val (cfg, areas) = call<Pair<CapabilityConfig, List<SeatArea>>> { ok, err ->
                s.checkSeatOccupancyCapabilities(onComplete = { c, a -> ok(c to a) }, onFailed = err)
            }
            mark(SeatFeature.OCCUPANCY, cfg, areas.toSeatIds())
        }
        val fa = foreAft
        tryCheck(SeatFeature.FORE_AFT_MOVE) {
            val f = fa?.takeIf { it.canMove } ?: throw VehicleCallException("NO_PUBLIC_METHOD")
            val (access, seats) = f.checkMove(::callAny)
            SeatId.entries.forEach { id -> result[id]!![SeatFeature.FORE_AFT_MOVE] = if (id in seats) access else Support.UNSUPPORTED }
        }
        tryCheck(SeatFeature.FORE_AFT_POSITION) {
            val f = fa?.takeIf { it.canReadRange } ?: throw VehicleCallException("NO_PUBLIC_METHOD")
            val (access, ranges) = f.checkRange(::callAny)
            foreAftRanges = ranges
            SeatId.entries.forEach { id -> result[id]!![SeatFeature.FORE_AFT_POSITION] = if (id in ranges) access else Support.UNSUPPORTED }
        }
        _status.update { it.copy(support = result.mapValues { (_, v) -> v.toMap() }) }

        // 차량 서비스가 막 켜졌을 때는 확인이 실패하거나 빈 목록이 와서 "미지원"으로 굳는 일이 있음 (11/14 에뮬레이터 실측:
        // 등받이 이동이 전 좌석 미지원으로 남았는데 다시 물으면 4석 모두 읽기·제어). 이동 축은 몇 번 더 확인함.
        val emptyMove = listOf(SeatFeature.BACKREST_MOVE, SeatFeature.FORE_AFT_MOVE).filter { f -> result.values.none { it[f] == Support.READ_WRITE } }
        if ((failed.isNotEmpty() || emptyMove.isNotEmpty()) && capabilityRetries < CAPABILITY_RETRIES) {
            capabilityRetries++
            Log.i(TAG, "capability retry $capabilityRetries: failed=$failed emptyMove=$emptyMove")
            scope.launch {
                delay(CAPABILITY_RETRY_MS)
                refreshCapabilities()
            }
        }
    }

    @Volatile private var capabilityRetries = 0

    @Volatile private var backrestBounds: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    @Volatile private var foreAftRanges: Map<SeatId, IntRange> = emptyMap()

    override suspend fun positionRange(seat: SeatId, axis: SeatAxis): IntRange? = when {
        console != null -> ConsoleSeatPositions.RANGE
        axis == SeatAxis.BACKREST -> backrestBounds.entries.firstOrNull { it.key.covers(seat) }?.value?.let { it.minValue..it.maxValue }
        else -> foreAftRanges[seat]
    }

    override suspend fun readPosition(seat: SeatId, axis: SeatAxis): Int? =
        if (console != null) console.read(seat, axis) else sdkPosition(seat, axis)

    private suspend fun sdkPosition(seat: SeatId, axis: SeatAxis): Int? = when (axis) {
        SeatAxis.BACKREST -> call<Int?> { ok, err ->
            seatApi().getCurrentSeatBackrestAnglePosition(pleosArea(seat), onComplete = { v -> ok(v) }, onFailed = err)
        }
        SeatAxis.FORE_AFT -> foreAft?.takeIf { it.canRead }?.read(pleosArea(seat), ::callAny)
    }

    override suspend fun move(seat: SeatId, axis: SeatAxis, direction: MoveDirection) {
        val control = when (direction) {
            MoveDirection.FORWARD -> MoveControl.FORWARD
            MoveDirection.BACKWARD -> MoveControl.BACKWARD
            MoveDirection.STOP -> MoveControl.STOP
        }
        when (axis) {
            SeatAxis.BACKREST -> call<Unit> { ok, err ->
                seatApi().moveSeatBackrestAngle(pleosArea(seat), control, onComplete = { _, _ -> ok(Unit) }, onFailed = err)
            }
            SeatAxis.FORE_AFT -> {
                val fa = foreAft?.takeIf { it.canMove } ?: throw VehicleCallException("ERROR_NOT_SUPPORTED_PROPERTY", "앞뒤 이동 공개 API를 찾지 못함")
                fa.move(pleosArea(seat), control, ::callAny)
            }
        }
    }

    override suspend fun demoNudge(seat: SeatId, axis: SeatAxis, deltaPercent: Int): Boolean =
        console?.nudge(seat, axis, deltaPercent) == true

    override suspend fun readHeat(seat: SeatId): Int? = call<Int?> { ok, err ->
        seatApi().getHvacSeatTemperature(pleosArea(seat), onComplete = { v -> ok(v) }, onFailed = err)
    }

    override suspend fun setHeat(seat: SeatId, level: Int) = call<Unit> { ok, err ->
        seatApi().setHvacSeatTemperature(pleosArea(seat), level, onComplete = { _, _ -> ok(Unit) }, onFailed = err)
    }

    override suspend fun readVent(seat: SeatId): Int? = call<Int?> { ok, err ->
        seatApi().getHvacSeatVentilationState(pleosArea(seat), onComplete = { v -> ok(v) }, onFailed = err)
    }

    override suspend fun setVent(seat: SeatId, level: Int) = call<Unit> { ok, err ->
        seatApi().setHvacSeatVentilation(pleosArea(seat), level, onComplete = { _, _ -> ok(Unit) }, onFailed = err)
    }

    /** Pleos HVAC: 지금 설정 온도를 읽고 앞줄 좌우를 같이 바꿈 (17~30°C) */
    override suspend fun nudgeCabinTemperature(deltaC: Float): Float? {
        val h = vehicle?.getHVAC() ?: return null
        val driver = pleosArea(SeatId.R1_LEFT)
        val cur = runCatching {
            call<Float?> { ok, err -> h.getHvacTemperature(driver, TemperatureUnit.CELSIUS, { v -> ok(v) }, err) }
        }.getOrNull() ?: return null
        val target = (cur + deltaC).coerceIn(17f, 30f)
        var ok = false
        for (s in listOf(SeatId.R1_LEFT, SeatId.R1_RIGHT)) {
            runCatching {
                call<Unit> { done, err -> h.setHvacTemperature(pleosArea(s), target, TemperatureUnit.CELSIUS, { _, _ -> done(Unit) }, err) }
            }.onSuccess { ok = true }.onFailure { Log.w(TAG, "setHvacTemperature ${s.name}: ${it.message}") }
        }
        return if (ok) target else null
    }

    // ---- 리스너 ----------------------------------------------------------------------------

    private fun registerListeners(v: Vehicle) {
        val s = v.getSeat()
        for (id in OCCUPANCY_SEATS) {
            val area = pleosArea(id)
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(value: Boolean?) {
                    val occupied = value?.let { if (occupancyTrueMeansVacant()) !it else it }
                    _status.update { it.copy(occupancy = it.occupancy + (id to occupied)) }
                    _events.tryEmit(VehicleEvent.OccupancyChanged(id, occupied))
                }

                override fun onFailed(e: Exception) {
                    Log.w(TAG, "occupancy $id: ${e.toCode()}")
                }
            }
            runCatching {
                s.registerSeatOccupancy(area, l)
                unregister += { s.unregisterSeatOccupancy(area, l) }
            }.onFailure { Log.w(TAG, "registerSeatOccupancy $id", it) }
        }

        val door = v.getDoor()
        for ((doorValue, seatId) in DOORS) {
            val area = DoorArea(doorValue)
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) {
                    val open = (pos ?: 0) > 0
                    _status.update { st -> st.copy(openDoors = if (open) st.openDoors + seatId else st.openDoors - seatId) }
                    _events.tryEmit(VehicleEvent.DoorChanged(seatId, open))
                }

                override fun onFailed(e: Exception) {
                    Log.w(TAG, "door $seatId: ${e.toCode()}")
                }
            }
            runCatching {
                door.registerDoorPosition(area, l)
                unregister += { door.unregisterDoorPosition(area, l) }
            }.onFailure { Log.w(TAG, "registerDoorPosition $seatId", it) }
        }

        // 문 잠금: 내릴 때 카시트에 아기가 남았는지 경고하는 데 씀.
        // 차량 서비스가 문 네 개를 한 영역(85)으로 알리므로 네 문을 묶어 등록해야 콜백이 옴 (실측)
        val lockArea = DoorArea(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
        val lockListener = object : DoorLockStateListener {
            override fun onDoorLockStateUpdated(locked: Boolean?) {
                if (locked != null) _events.tryEmit(VehicleEvent.DoorLockChanged(locked))
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "door lock: ${e.toCode()}")
            }
        }
        runCatching {
            door.registerDoorLockState(lockArea, lockListener)
            unregister += { door.unregisterDoorLockState(lockArea, lockListener) }
        }.onFailure { Log.w(TAG, "registerDoorLockState", it) }
    }

    // ---- 콜백 → 코루틴 ----------------------------------------------------------------------

    private suspend fun <T> call(block: (ok: (T) -> Unit, err: (Exception) -> Unit) -> Unit): T =
        withTimeout(CALL_TIMEOUT_MS) {
            suspendCancellableCoroutine { c ->
                try {
                    block({ v -> if (c.isActive) c.resume(v) }, { e -> if (c.isActive) c.resumeWithException(e.toCallException()) })
                } catch (e: Exception) {
                    if (c.isActive) c.resumeWithException(e.toCallException())
                }
            }
        }

    private suspend fun callAny(block: (ok: (Any?) -> Unit, err: (Exception) -> Unit) -> Unit): Any? = call(block)

    private fun Exception.toCallException(): VehicleCallException = this as? VehicleCallException ?: VehicleCallException(toCode(), message)

    companion object {
        private const val TAG = "PleosVehicle"
        private const val CALL_TIMEOUT_MS = 3_000L
        private const val CONSOLE_POLL_MS = 400L
        private const val SDK_POLL_MS = 1_000L

        /** 지원 기능 확인이 실패하거나 이동 축이 하나도 없을 때 다시 확인하는 횟수 · 간격 */
        private const val CAPABILITY_RETRIES = 5
        private const val CAPABILITY_RETRY_MS = 5_000L

        /** 운전석은 착좌 확인 대상이 아님 */
        private val OCCUPANCY_SEATS = listOf(SeatId.R1_RIGHT, SeatId.R2_LEFT, SeatId.R2_RIGHT, SeatId.R3_LEFT, SeatId.R3_RIGHT)

        /** 문 → 그 문으로 타는 좌석 */
        private val DOORS = listOf(
            DoorValue.ROW_1_RIGHT to SeatId.R1_RIGHT,
            DoorValue.ROW_2_LEFT to SeatId.R2_LEFT,
            DoorValue.ROW_2_RIGHT to SeatId.R2_RIGHT,
        )

    }
}

/** SDK 콜백 기반 호출을 코루틴으로 감싼 함수 (리플렉션 호출용) */
typealias SdkCall = suspend (block: (ok: (Any?) -> Unit, err: (Exception) -> Unit) -> Unit) -> Any?

internal fun pleosArea(seat: SeatId): SeatArea = SeatArea(seat.toSeatValue())

internal fun SeatId.toSeatValue(): SeatValue = when (this) {
    SeatId.R1_LEFT -> SeatValue.ROW_1_LEFT
    SeatId.R1_RIGHT -> SeatValue.ROW_1_RIGHT
    SeatId.R2_LEFT -> SeatValue.ROW_2_LEFT
    SeatId.R2_RIGHT -> SeatValue.ROW_2_RIGHT
    SeatId.R3_LEFT -> SeatValue.ROW_3_LEFT
    SeatId.R3_RIGHT -> SeatValue.ROW_3_RIGHT
}

internal fun SeatValue.toSeatId(): SeatId? = SeatId.entries.firstOrNull { it.toSeatValue() == this }

/** SeatArea 는 data class 가 아니라서 값 배열로 비교. 여러 좌석을 묶은 영역도 포함 여부로 판단. */
internal fun SeatArea.covers(seat: SeatId): Boolean = values.any { it == seat.toSeatValue() }

internal fun Collection<SeatArea>.toSeatIds(): Set<SeatId> = flatMap { a -> a.values.mapNotNull { it.toSeatId() } }.toSet()

internal fun CapabilityConfig.toSupport(): Support = when (accessType) {
    AccessType.READ_WRITE -> Support.READ_WRITE
    AccessType.WRITE -> Support.READ_WRITE
    AccessType.READ -> Support.READ_ONLY
    else -> Support.UNSUPPORTED
}

/**
 * 2.3.2 의 VehicleException 은 클래스 이름이 난독화되어 있어 타입으로 잡을 수 없음.
 * message 가 ErrorCode 이름("ERROR_SECURITY_PERMISSION" 등)이라 그걸로 복원.
 */
internal fun Throwable.toCode(): String {
    val t = if (this is InvocationTargetException) targetException ?: this else this
    if (t is VehicleCallException) return t.code
    val m = t.message
    return m ?: (t::class.java.simpleName + (m?.let { ": $it" } ?: ""))
}

/**
 * 앞뒤(Fore/Aft) 이동은 공개 예제·문서에서 정확한 메서드 이름을 확인하지 못해,
 * 공식 샘플의 API 탐색기처럼 Seat 의 public 메서드 중 이름에 "ForeAft" 가 있는 것을 찾아 호출함.
 * 찾은 이름은 설정 > SDK 진단 화면에 그대로 보여 주므로 실제 이름을 확인한 뒤 타입 호출로 바꾸면 됨.
 */
class SeatForeAftReflection(private val seat: Any) {
    private val methods = seat.javaClass.methods.filter { it.name.contains("ForeAft", ignoreCase = true) }
    val found: List<String> = methods.map { m -> "${m.name}(${m.parameterTypes.joinToString { it.simpleName }})" }.distinct().sorted()

    private val moveMethod = methods.firstOrNull {
        it.name.startsWith("move") && it.parameterTypes.size == 4 && it.parameterTypes[1] == MoveControl::class.java
    }
    private val readMethod = methods.firstOrNull {
        it.name.startsWith("getCurrent") && it.parameterTypes.size == 3 && it.parameterTypes[0] == SeatArea::class.java
    }
    private val moveCapMethod = methods.firstOrNull { it.name.startsWith("checkMove") && it.parameterTypes.size == 2 }
    private val rangeCapMethod = methods.firstOrNull {
        it.name.startsWith("check") && !it.name.startsWith("checkMove") && it.name.contains("Position") && it.parameterTypes.size == 2
    }

    val canMove get() = moveMethod != null
    val canRead get() = readMethod != null
    val canReadRange get() = rangeCapMethod != null

    private fun invoke(m: java.lang.reflect.Method, vararg args: Any?) {
        try {
            m.invoke(seat, *args)
        } catch (e: InvocationTargetException) {
            throw (e.targetException as? Exception ?: e)
        }
    }

    suspend fun move(area: SeatArea, control: MoveControl, call: SdkCall) {
        val m = moveMethod ?: throw VehicleCallException("ERROR_NOT_SUPPORTED_PROPERTY")
        call { ok, err ->
            val onComplete: (Any?, Any?) -> Unit = { _, _ -> ok(Unit) }
            invoke(m, area, control, onComplete, err)
        }
    }

    suspend fun read(area: SeatArea, call: SdkCall): Int? {
        val m = readMethod ?: return null
        return call { ok, err ->
            val onComplete: (Any?) -> Unit = { v -> ok(v) }
            invoke(m, area, onComplete, err)
        } as? Int
    }

    suspend fun checkMove(call: SdkCall): Pair<Support, Set<SeatId>> {
        val m = moveCapMethod ?: throw VehicleCallException("ERROR_NOT_SUPPORTED_PROPERTY")
        val r = call { ok, err ->
            val onComplete: (Any?, Any?) -> Unit = { c, a -> ok(c to a) }
            invoke(m, onComplete, err)
        } as Pair<*, *>
        val cfg = r.first as CapabilityConfig
        val areas = (r.second as? Collection<*>)?.filterIsInstance<SeatArea>().orEmpty()
        return cfg.toSupport() to areas.toSeatIds()
    }

    suspend fun checkRange(call: SdkCall): Pair<Support, Map<SeatId, IntRange>> {
        val m = rangeCapMethod ?: throw VehicleCallException("ERROR_NOT_SUPPORTED_PROPERTY")
        val r = call { ok, err ->
            val onComplete: (Any?, Any?) -> Unit = { c, b -> ok(c to b) }
            invoke(m, onComplete, err)
        } as Pair<*, *>
        val cfg = r.first as CapabilityConfig
        val ranges = mutableMapOf<SeatId, IntRange>()
        (r.second as? Map<*, *>)?.forEach { (k, v) ->
            val area = k as? SeatArea ?: return@forEach
            val b = v as? AreaBounds<*> ?: return@forEach
            val min = (b.minValue as? Number)?.toInt() ?: return@forEach
            val max = (b.maxValue as? Number)?.toInt() ?: return@forEach
            area.values.mapNotNull { it.toSeatId() }.forEach { ranges[it] = min..max }
        }
        return cfg.toSupport() to ranges
    }
}
