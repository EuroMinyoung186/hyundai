package ai.moduui.jari.control

import ai.moduui.core.model.DrivingState
import ai.moduui.core.model.SeatId
import ai.moduui.core.seat.Executor
import ai.moduui.core.seat.SeatAction
import ai.moduui.core.seat.SeatAxis
import ai.moduui.core.seat.SeatStep
import ai.moduui.jari.vehicle.MoveDirection
import ai.moduui.jari.vehicle.SeatFeature
import ai.moduui.jari.vehicle.Support
import ai.moduui.jari.vehicle.VehicleCallException
import ai.moduui.jari.vehicle.VehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

enum class StepOutcome(val labelKo: String, val terminal: Boolean = true) {
    PENDING("대기", terminal = false),
    RUNNING("움직이는 중", terminal = false),
    DONE("완료"),
    SENT("명령 전달 (위치 확인 불가)"),
    GUIDE("직접 해 주세요"),
    /** 에뮬레이터 시연 전용: 실제 SDK 는 거부했고 콘솔 값으로만 표현함 */
    SIMULATED("시연으로 표시"),
    SKIPPED_OCCUPIED("사람이 앉아 있어 건너뜀"),
    SKIPPED_DRIVING("주행 중이라 멈춤"),
    STOPPED_DOOR("문이 열려 멈춤"),
    UNSUPPORTED("이 차량 미지원"),
    NO_PERMISSION("제어 권한 없음 (Vehicle Control 승인 필요)"),
    FAILED("실패"),
    CANCELLED("취소됨"),
}

data class StepState(val step: SeatStep, val outcome: StepOutcome = StepOutcome.PENDING, val detail: String? = null)

data class RunState(
    val reasonKo: String,
    val steps: List<StepState>,
    val countdown: Int? = null,
    val finished: Boolean = false,
    val canUndo: Boolean = false,
) {
    val doneCount: Int get() = steps.count { it.outcome == StepOutcome.DONE || it.outcome == StepOutcome.SENT }
}

/**
 * 좌석 단계를 하나씩 안전하게 실행.
 *
 * 안전 장치 (심사 피드백: "움직이는 동작을 자동화할 때 안전 설계가 핵심 경쟁력")
 *  1. 시작 전 카운트다운 (기본 5초) — 화면 [취소] 또는 Gleo/버튼으로 멈출 수 있음
 *  2. P단(정차)에서만. 도중에 P가 풀리면 즉시 STOP 후 중단
 *  3. 움직일 좌석에 사람이 앉아 있으면(착좌 센서) 그 단계 건너뜀
 *  4. 움직이는 좌석 쪽 문이 열리면 즉시 STOP 후 중단 (손이 들어올 수 있음)
 *  5. 한 번에 한 좌석, 한 축만. 위치 피드백이 없으면 최대 3초만 움직이고 멈춤
 *  6. 이전 위치·열선·통풍 값을 기억해 [되돌리기]
 */
class SeatStepRunner(
    private val vehicle: VehicleGateway,
    private val drivingState: () -> DrivingState,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val pollMs: Long = 150,
        val moveTimeoutMs: Long = 6_000,
        val noChangeTimeoutMs: Long = 2_000,
        val blindMoveMs: Long = 3_000,
    )

    private sealed interface Undo {
        data class Position(val seat: SeatId, val axis: SeatAxis, val value: Int) : Undo
        data class Heat(val seat: SeatId, val level: Int) : Undo
        data class Vent(val seat: SeatId, val level: Int) : Undo
    }

    private val _state = MutableStateFlow<RunState?>(null)
    val state: StateFlow<RunState?> = _state
    private var job: Job? = null
    private val undo = mutableListOf<Undo>()

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * @param keepUndo 세팅 화면 [적용]을 손을 뗐다가 다시 누른 경우처럼 같은 작업을 이어서 할 때 true.
     *   처음 기록한 원래 위치를 지우지 않아야 [되돌리기]가 실행 전 상태로 돌아감.
     */
    fun run(steps: List<SeatStep>, reasonKo: String, countdownSeconds: Int = 5, keepUndo: Boolean = false) {
        job?.cancel()
        if (!keepUndo) undo.clear()
        _state.value = RunState(reasonKo, steps.map { StepState(it) }, countdown = countdownSeconds.takeIf { it > 0 })
        job = scope.launch {
            for (s in countdownSeconds downTo 1) {
                _state.update { it?.copy(countdown = s) }
                delay(1_000)
            }
            _state.update { it?.copy(countdown = null) }
            execute(steps)
        }
    }

    fun cancel() {
        val running = job
        job = null
        running?.cancel()
        scope.launch {
            withContext(NonCancellable) { stopAll() }
            _state.update { st ->
                st?.copy(
                    countdown = null,
                    finished = true,
                    canUndo = undo.isNotEmpty(),
                    steps = st.steps.map { if (!it.outcome.terminal) it.copy(outcome = StepOutcome.CANCELLED) else it },
                )
            }
        }
    }

    fun dismiss() {
        if (!isRunning) _state.value = null
    }

    /** 실행 전 상태로 되돌리기 (정차 중에만) */
    fun undoLast() {
        if (undo.isEmpty()) return
        // 실행 작업은 finished 표시 직후 아주 잠깐 살아 있을 수 있음 → 끝난 상태면 그 작업이 마무리되길 기다렸다가 되돌림
        if (isRunning && _state.value?.finished != true) return
        val previous = job
        val items = undo.reversed().toList()
        undo.clear()
        job = scope.launch {
            previous?.join()
            _state.update { it?.copy(reasonKo = "되돌리는 중", canUndo = false) }
            for (u in items) {
                if (drivingState() != DrivingState.PARKED) break
                runCatching {
                    when (u) {
                        is Undo.Position -> moveToValue(u.seat, u.axis, u.value, record = false)
                        is Undo.Heat -> vehicle.setHeat(u.seat, u.level)
                        is Undo.Vent -> vehicle.setVent(u.seat, u.level)
                    }
                }.onFailure { log("되돌리기 실패: ${it.message}") }
            }
            _state.update { it?.copy(reasonKo = "되돌렸어요", finished = true) }
        }
    }

    private suspend fun execute(steps: List<SeatStep>) {
        for ((i, step) in steps.withIndex()) {
            if (step.executor == Executor.GUIDE) {
                set(i, StepOutcome.GUIDE)
                continue
            }
            if (drivingState() != DrivingState.PARKED) {
                markRest(i, StepOutcome.SKIPPED_DRIVING)
                break
            }
            if (vehicle.status.value.occupancy[step.seat] == true && step.action is SeatAction.Move) {
                set(i, StepOutcome.SKIPPED_OCCUPIED)
                continue
            }
            set(i, StepOutcome.RUNNING)
            val (outcome, detail) = try {
                when (val a = step.action) {
                    is SeatAction.Move -> moveToFraction(step.seat, a.axis, a.targetFraction)
                    is SeatAction.Heat -> {
                        vehicle.readHeat(step.seat)?.let { undo += Undo.Heat(step.seat, it) }
                        vehicle.setHeat(step.seat, a.level)
                        StepOutcome.DONE to null
                    }
                    is SeatAction.Vent -> {
                        vehicle.readVent(step.seat)?.let { undo += Undo.Vent(step.seat, it) }
                        vehicle.setVent(step.seat, a.level)
                        StepOutcome.DONE to null
                    }
                    SeatAction.Fold, SeatAction.Unfold -> StepOutcome.GUIDE to null
                }
            } catch (e: VehicleCallException) {
                // 지원 목록에 없는 기능(2열 통풍 등)의 실패는 이 차에 없는 것
                if (reportedUnsupported(step)) StepOutcome.UNSUPPORTED to null else mapError(e)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                StepOutcome.FAILED to "응답 없음"
            }
            set(i, outcome, detail)
            log("${step.seat.labelKo} ${step.action.labelKo}: ${outcome.labelKo}${detail?.let { " ($it)" } ?: ""}")
            if (outcome == StepOutcome.SKIPPED_DRIVING || outcome == StepOutcome.STOPPED_DOOR) {
                markRest(i + 1, StepOutcome.CANCELLED)
                break
            }
        }
        _state.update { it?.copy(finished = true, canUndo = undo.isNotEmpty()) }
    }

    /**
     * 세팅 화면의 미세 조절 버튼. 한 번 누르면 [NUDGE_MS] 동안만 움직이고 멈춤.
     * 자동 실행과 같은 조건(P단 · 빈 좌석 · 같은 쪽 문 닫힘)을 시작 전과 움직이는 동안 모두 확인함.
     */
    fun nudge(seat: SeatId, axis: SeatAxis, dir: MoveDirection) {
        if (isRunning || dir == MoveDirection.STOP) return
        job = scope.launch {
            val blocked = when {
                drivingState() != DrivingState.PARKED -> StepOutcome.SKIPPED_DRIVING
                vehicle.status.value.occupancy[seat] == true -> StepOutcome.SKIPPED_OCCUPIED
                else -> interlock(seat)
            }
            if (blocked != null) {
                log("${seat.labelKo} ${axis.labelKo} 조절 안 함: ${blocked.labelKo}")
                return@launch
            }
            runCatching { vehicle.readPosition(seat, axis) }.getOrNull()?.let { undo += Undo.Position(seat, axis, it) }
            try {
                vehicle.move(seat, axis, dir)
                val startedAt = System.currentTimeMillis()
                while (System.currentTimeMillis() - startedAt < NUDGE_MS) {
                    delay(timing.pollMs.coerceAtMost(100))
                    interlock(seat)?.let {
                        log("${seat.labelKo} ${axis.labelKo} 조절 멈춤: ${it.labelKo}")
                        return@launch
                    }
                }
            } catch (e: VehicleCallException) {
                log("${seat.labelKo} ${axis.labelKo} 조절 실패: ${mapError(e).let { (o, d) -> o.labelKo + (d?.let { " ($it)" } ?: "") }}")
            } finally {
                stopSafely(seat, axis)
                markUndoable()
            }
        }
    }

    private data class SmallMove(val seat: SeatId, val axis: SeatAxis, val dir: MoveDirection, val ms: Long, val demoPercent: Int? = null)

    private val smallMoves = mutableListOf<SmallMove>()

    /**
     * 주행 중 소폭 조절. 이 경로만 P단이 아니어도 움직이고 사람이 앉은 좌석도 움직임
     * (기획 안전 원칙: 주행 중에는 알린 뒤 작은 조절만). 그래서 시간으로 폭을 제한함.
     * 실제 좌석은 에뮬레이터(0.1초에 5%)보다 느리므로 허용 폭보다 덜 움직이는 쪽으로 안전함.
     * 운전석은 절대 움직이지 않음. 큰 이동(execute)이 진행 중이면 하지 않음.
     *
     * 주의: Pleos 차량 서비스는 P단이 아니면 좌석 명령을 거부함 (에뮬레이터 실측, 코드 50001).
     * 거부되면 SKIPPED_DRIVING 으로 끝나고, 에뮬레이터 시연에서만 콘솔 값으로 표시함 (SIMULATED).
     */
    suspend fun smallAdjust(seat: SeatId, axis: SeatAxis, towardPositive: Boolean, maxPercent: Int): StepOutcome {
        if (seat.isDriver) return StepOutcome.UNSUPPORTED
        if (isRunning) return StepOutcome.CANCELLED
        if (vehicle.status.value.openDoors.any { it == seat || it.side == seat.side }) return StepOutcome.STOPPED_DOOR
        val dir = if (towardPositive) MoveDirection.FORWARD else MoveDirection.BACKWARD
        val ms = (maxPercent * MS_PER_PERCENT).coerceIn(MS_PER_PERCENT, SMALL_MAX_MS)
        val outcome = pulse(seat, axis, dir, ms)
        if (outcome == StepOutcome.DONE) smallMoves += SmallMove(seat, axis, dir, ms)
        if (outcome == StepOutcome.SKIPPED_DRIVING) {
            val signed = if (towardPositive) maxPercent else -maxPercent
            if (vehicle.demoNudge(seat, axis, signed)) {
                smallMoves += SmallMove(seat, axis, dir, ms, demoPercent = signed)
                return StepOutcome.SIMULATED
            }
        }
        return outcome
    }

    /** 소폭 조절을 같은 시간만큼 반대로 움직여 되돌림 (주행 중에도 가능) */
    suspend fun undoSmall() {
        val moves = smallMoves.reversed()
        smallMoves.clear()
        for (m in moves) {
            if (m.demoPercent != null) {
                vehicle.demoNudge(m.seat, m.axis, -m.demoPercent)
                continue
            }
            val back = if (m.dir == MoveDirection.FORWARD) MoveDirection.BACKWARD else MoveDirection.FORWARD
            val outcome = pulse(m.seat, m.axis, back, m.ms)
            if (outcome != StepOutcome.DONE) log("${m.seat.labelKo} 되돌리기: ${outcome.labelKo}")
        }
    }

    private suspend fun pulse(seat: SeatId, axis: SeatAxis, dir: MoveDirection, ms: Long): StepOutcome =
        try {
            val startedAt = System.currentTimeMillis()
            vehicle.move(seat, axis, dir)
            // 명령이 오가는 동안에도 좌석은 움직임. 정지 명령도 비슷하게 걸린다고 보고 그만큼 일찍 멈춤
            val callMs = System.currentTimeMillis() - startedAt
            delay((ms - callMs * 2).coerceAtLeast(0))
            StepOutcome.DONE
        } catch (e: VehicleCallException) {
            val mapped = mapError(e).first
            log("${seat.labelKo} ${axis.labelKo} 소폭 조절 거부: ${e.code}")
            // 차량 서비스는 P단이 아니면 좌석 명령을 거부함
            if (mapped == StepOutcome.FAILED && drivingState() != DrivingState.PARKED) StepOutcome.SKIPPED_DRIVING else mapped
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            StepOutcome.FAILED
        } finally {
            stopSafely(seat, axis)
        }

    /** 미세 조절만 한 경우에도 [되돌리기]가 켜지도록 상태를 만들어 둠 */
    private fun markUndoable() {
        _state.update { (it ?: RunState("직접 조절", emptyList(), finished = true)).copy(canUndo = undo.isNotEmpty()) }
    }

    /** 열선 켜기/끄기 (켜져 있으면 끄고, 꺼져 있으면 1단). 정차 중에만. */
    fun toggleHeat(seat: SeatId) {
        if (isRunning) return
        job = scope.launch {
            if (drivingState() != DrivingState.PARKED) return@launch
            try {
                val now = vehicle.readHeat(seat)
                now?.let { undo += Undo.Heat(seat, it) }
                vehicle.setHeat(seat, if ((now ?: 0) > 0) 0 else 1)
            } catch (e: VehicleCallException) {
                log("${seat.labelKo} 열선 바꾸기 실패: ${mapError(e).first.labelKo}")
            }
            markUndoable()
        }
    }

    private fun reportedUnsupported(step: SeatStep): Boolean {
        val feature = when (val a = step.action) {
            is SeatAction.Move -> if (a.axis == SeatAxis.BACKREST) SeatFeature.BACKREST_MOVE else SeatFeature.FORE_AFT_MOVE
            is SeatAction.Heat -> SeatFeature.HEAT
            is SeatAction.Vent -> SeatFeature.VENT
            else -> return false
        }
        return vehicle.status.value.support(step.seat, feature) == Support.UNSUPPORTED
    }

    private fun mapError(e: VehicleCallException): Pair<StepOutcome, String?> = when {
        e.code.startsWith("ERROR_NOT_SUPPORTED") || e.code == "NO_PUBLIC_METHOD" -> StepOutcome.UNSUPPORTED to e.message
        e.code == "ERROR_SECURITY_PERMISSION" -> StepOutcome.NO_PERMISSION to null
        e.code == "ERROR_DRIVING_RESTRICTED" -> StepOutcome.SKIPPED_DRIVING to null
        else -> StepOutcome.FAILED to e.code
    }

    private suspend fun moveToFraction(seat: SeatId, axis: SeatAxis, fraction: Float): Pair<StepOutcome, String?> {
        val range = runCatching { vehicle.positionRange(seat, axis) }.getOrNull()
        if (range == null || range.last <= range.first) return blindMove(seat, axis, if (fraction >= 0.5f) MoveDirection.FORWARD else MoveDirection.BACKWARD)
        val target = range.first + ((range.last - range.first) * fraction).roundToInt()
        return moveToValue(seat, axis, target, record = true, range = range)
    }

    /** 위치를 읽으며 목표까지 움직이고 멈춤. 위치가 안 바뀌면(에뮬레이터 등) SENT 로 끝냄. */
    private suspend fun moveToValue(
        seat: SeatId,
        axis: SeatAxis,
        target: Int,
        record: Boolean,
        range: IntRange? = null,
    ): Pair<StepOutcome, String?> {
        val start = runCatching { vehicle.readPosition(seat, axis) }.getOrNull()
            ?: return blindMove(seat, axis, if (range != null && target >= (range.first + range.last) / 2) MoveDirection.FORWARD else MoveDirection.BACKWARD)
        val tolerance = range?.let { ((it.last - it.first) * 0.02).roundToInt().coerceAtLeast(1) } ?: 1
        if (abs(start - target) <= tolerance) return StepOutcome.DONE to "이미 맞는 위치"
        if (record) undo += Undo.Position(seat, axis, start)
        val dir = if (target > start) MoveDirection.FORWARD else MoveDirection.BACKWARD
        vehicle.move(seat, axis, dir)
        var last = start
        var lastChangeAt = System.currentTimeMillis()
        val startedAt = lastChangeAt
        try {
            while (true) {
                delay(timing.pollMs)
                interlock(seat)?.let { return it to null }
                val pos = runCatching { vehicle.readPosition(seat, axis) }.getOrNull() ?: return StepOutcome.SENT to "위치를 읽을 수 없음"
                val now = System.currentTimeMillis()
                // 멈추는 사이에도 움직이므로 직전 읽기 변화의 절반만큼 더 간다고 보고 미리 멈춤. 양 끝은 끝까지 가도 안전함.
                val atEnd = range != null && (target <= range.first || target >= range.last)
                val lead = if (atEnd) 0 else abs(pos - last) / 2
                if (pos != last) {
                    last = pos
                    lastChangeAt = now
                }
                if ((dir == MoveDirection.FORWARD && pos + lead >= target - tolerance) || (dir == MoveDirection.BACKWARD && pos - lead <= target + tolerance)) {
                    stopSafely(seat, axis)
                    // 멈추는 사이에 목표를 지나쳤으면 짧게 끊어 움직이며 되돌림 (11/14 에뮬레이터: 앞뒤 65 → 75, 등받이 89 → 100).
                    // 위치 읽기가 늦어서 읽으며 멈추면 또 지나치므로, 처음 움직인 속도로 짧게 움직이고 멈춘 뒤 다시 읽음
                    val (o, end) = fineTune(seat, axis, target, tolerance, start, (System.currentTimeMillis() - startedAt).coerceAtLeast(1), pos)
                    if (o != StepOutcome.DONE) return o to null
                    return StepOutcome.DONE to "$start → $end"
                }
                if (now - lastChangeAt > timing.noChangeTimeoutMs) return StepOutcome.SENT to "위치 변화 없음"
                if (now - startedAt > timing.moveTimeoutMs) return StepOutcome.SENT to "시간 초과 $start → $pos"
            }
        } finally {
            stopSafely(seat, axis)
        }
    }

    /**
     * 목표 가까이에서 짧게 끊어 움직이며 맞춤. 움직일 때마다 P단 · 문을 다시 확인.
     * 속도(위치 단위 / ms)는 멈춘 뒤 자리 잡은 위치로 재고(읽기가 늦어 움직이는 중 값은 덜 간 것으로 나옴),
     * 짧게 움직일 때마다 실제로 간 거리로 고침. 남은 거리의 60% 만큼만 움직이고, 방향이 바뀔 때마다 절반으로 줄임.
     * 방향이 네 번 바뀌면(왔다 갔다) 그만둠. 반환: (결과, 마지막 위치)
     */
    private suspend fun fineTune(seat: SeatId, axis: SeatAxis, target: Int, tolerance: Int, start: Int, movedMs: Long, fallback: Int): Pair<StepOutcome, Int> {
        delay(SETTLE_MS)
        var cur = runCatching { vehicle.readPosition(seat, axis) }.getOrNull() ?: return StepOutcome.DONE to fallback
        var speed = abs(cur - start).toDouble() / movedMs
        if (speed <= 0.0) return StepOutcome.DONE to cur
        var lastDir: MoveDirection? = null
        var flips = 0
        // 방향이 바뀔 때마다 절반씩 줄여 점점 작게 다가감 (에뮬레이터 좌석은 5% 단위로 움직여 한 번에 건너뛰기 쉬움)
        var scale = 0.6
        repeat(FINE_PULSES) {
            val err = target - cur
            if (abs(err) <= tolerance) return StepOutcome.DONE to cur
            val dir = if (err > 0) MoveDirection.FORWARD else MoveDirection.BACKWARD
            if (lastDir != null && dir != lastDir) {
                if (++flips >= 4) return StepOutcome.DONE to cur
                scale /= 2
            }
            lastDir = dir
            interlock(seat)?.let { return it to cur }
            val ms = (abs(err) / speed * scale).toLong().coerceIn(PULSE_MIN_MS, PULSE_MAX_MS)
            try {
                vehicle.move(seat, axis, dir)
                delay(ms)
            } finally {
                stopSafely(seat, axis)
            }
            delay(SETTLE_MS)
            val before = cur
            cur = runCatching { vehicle.readPosition(seat, axis) }.getOrNull() ?: return StepOutcome.DONE to cur
            // 이번에 실제로 간 거리로 속도를 고침 (짧게 움직일 때는 명령이 닿는 시간까지 들어감)
            if (cur != before) speed = abs(cur - before).toDouble() / ms
        }
        return StepOutcome.DONE to cur
    }

    /**
     * 멈춤 명령. 실패하면 몇 번 더 보냄 (11/14 에뮬레이터: 차량 서비스가 잠깐 다시 붙는 사이 멈춤이 닿지 않아
     * 좌석이 끝까지 감). 취소되는 중에도 반드시 보냄.
     */
    private suspend fun stopSafely(seat: SeatId, axis: SeatAxis) = withContext(NonCancellable) {
        for (i in 1..STOP_TRIES) {
            if (runCatching { vehicle.move(seat, axis, MoveDirection.STOP) }.isSuccess) return@withContext
            log("${seat.labelKo} ${axis.labelKo} 멈춤 명령이 닿지 않아 다시 보내요 ($i)")
            delay(STOP_RETRY_MS)
        }
    }

    /** 위치 범위·현재값을 모를 때: 정해진 방향으로 짧게만 움직이고 멈춤 */
    private suspend fun blindMove(seat: SeatId, axis: SeatAxis, dir: MoveDirection): Pair<StepOutcome, String?> {
        vehicle.move(seat, axis, dir)
        val startedAt = System.currentTimeMillis()
        try {
            while (System.currentTimeMillis() - startedAt < timing.blindMoveMs) {
                delay(timing.pollMs)
                interlock(seat)?.let { return it to null }
            }
            return StepOutcome.SENT to "위치 정보 없음, ${timing.blindMoveMs / 1000}초만 이동"
        } finally {
            stopSafely(seat, axis)
        }
    }

    private fun interlock(seat: SeatId): StepOutcome? {
        if (drivingState() != DrivingState.PARKED) return StepOutcome.SKIPPED_DRIVING
        val doors = vehicle.status.value.openDoors
        // 같은 쪽 문(그 좌석 또는 같은 쪽 다른 열)이 열리면 멈춤
        if (doors.any { it == seat || it.side == seat.side }) return StepOutcome.STOPPED_DOOR
        return null
    }

    private suspend fun stopAll() {
        val st = _state.value ?: return
        st.steps.filter { it.outcome == StepOutcome.RUNNING }.forEach { s ->
            val a = s.step.action as? SeatAction.Move ?: return@forEach
            runCatching { vehicle.move(s.step.seat, a.axis, MoveDirection.STOP) }
        }
    }

    private fun set(index: Int, outcome: StepOutcome, detail: String? = null) {
        _state.update { st ->
            st?.copy(steps = st.steps.mapIndexed { i, s -> if (i == index) s.copy(outcome = outcome, detail = detail) else s })
        }
    }

    private fun markRest(from: Int, outcome: StepOutcome) {
        _state.update { st ->
            st?.copy(steps = st.steps.mapIndexed { i, s -> if (i >= from && !s.outcome.terminal) s.copy(outcome = outcome) else s })
        }
    }

    companion object {
        const val NUDGE_MS = 400L
        const val MS_PER_PERCENT = 20L
        const val SMALL_MAX_MS = 200L

        /** 목표 가까이에서 짧게 끊어 맞추는 최대 횟수 · 한 번 움직이는 시간 · 멈춘 뒤 위치가 자리 잡을 때까지 기다리는 시간 */
        const val FINE_PULSES = 8
        const val PULSE_MIN_MS = 40L
        const val PULSE_MAX_MS = 600L
        const val SETTLE_MS = 800L

        /** 멈춤 명령을 다시 보내는 횟수 · 간격 */
        const val STOP_TRIES = 4
        const val STOP_RETRY_MS = 300L
    }
}
