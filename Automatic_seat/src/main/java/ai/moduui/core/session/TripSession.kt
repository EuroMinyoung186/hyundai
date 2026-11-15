package ai.moduui.core.session

import ai.moduui.core.model.BabyProfile
import ai.moduui.core.model.BabyState
import ai.moduui.core.model.DrivingState
import ai.moduui.core.model.RouteSnapshot
import ai.moduui.core.model.SeatId
import ai.moduui.core.model.StateSource
import ai.moduui.core.model.TripRequest
import ai.moduui.core.seat.Executor
import ai.moduui.core.seat.Occupant
import ai.moduui.core.seat.SeatAction
import ai.moduui.core.seat.SeatAxis
import ai.moduui.core.seat.SeatPlan
import ai.moduui.core.seat.SeatPlanner
import ai.moduui.core.seat.SeatStep
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed interface SessionEvent {
    data class PlanRequested(val trip: TripRequest) : SessionEvent
    data object DepartureConfirmed : SessionEvent
    data class DrivingChanged(val state: DrivingState) : SessionEvent
    data class BabyReported(val state: BabyState, val source: StateSource) : SessionEvent
    data class FeedLogged(val at: LocalDateTime? = null) : SessionEvent

    /** 출발 알림에서 정한 다음 수유 시각 (null = 수유 간격으로 계산한 값 그대로) */
    data class FeedDueSet(val at: LocalDateTime?) : SessionEvent

    /** 출발 알림에서 정한 밥 시간 (null = 안 먹음) */
    data class MealSet(val at: LocalDateTime?) : SessionEvent

    /** 이번 여정에서 Baby Seat 기능(수유 · 밥 휴게소 제안)을 쓸지. 출발 알림 [예]/[아니요] */
    data class AssistSet(val on: Boolean) : SessionEvent
    data class RouteUpdated(val route: RouteSnapshot) : SessionEvent
    data object DestinationArrived : SessionEvent
    data object Tick : SessionEvent
}

/**
 * 여정 하나의 상태 기계. 탈 때 → 달리는 동안 → 내릴 때.
 *
 * 입력: 차·내비·Gleo·버튼에서 온 이벤트. 출력: CarAction 목록.
 * 안전 원칙
 *  1. 큰 좌석 이동은 정차(P) · 빈 자리에서만. 주행 중 요청은 대기열에 넣고 정차하면 카운트다운 후 실행.
 *     주행 중에는 알린 뒤 소폭 조절만 (보호자 자리). 아기가 탄 카시트 좌석은 프로필의 허용 폭이 있을 때만.
 *  2. 주행 중에는 음성·큰 아이콘만. 긴 글·입력 화면 없음.
 *  3. 판단 규칙은 결정적(같은 입력 → 같은 출력)이라 테스트로 고정함.
 */
class TripSession(
    profile: BabyProfile,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    var profile: BabyProfile = profile
        private set

    var trip: TripRequest? = null
        private set
    var plan: SeatPlan? = null
        private set
    var drivingState: DrivingState = DrivingState.UNKNOWN
        private set
    var babyState: BabyState = BabyState.UNKNOWN
        private set
    var babyStateSince: LocalDateTime = clock()
        private set
    var babyStateSource: StateSource = StateSource.BUTTON
        private set
    var lastFeedAt: LocalDateTime? = null
        private set

    /** 보호자가 정한 다음 수유 시각. 수유를 기록하면 지움 (그다음은 간격으로 계산) */
    var feedDueOverride: LocalDateTime? = null
        private set

    /** 보호자가 정한 밥 시간 (휴게소 제안). 없으면 설정의 아기 밥 시간 중 다음 것 */
    var mealAt: LocalDateTime? = null
        private set

    /** 이번 여정에서 수유 · 밥 휴게소 제안을 함 (출발 알림 [아니요]면 끔) */
    var assistOn: Boolean = true
        private set

    /** 다음 밥 시각: 정한 시각, 없으면 설정의 밥 시간 중 지금(5분 전까지 포함) 뒤로 가장 가까운 것 */
    fun nextMealAt(now: LocalDateTime = clock()): LocalDateTime? {
        mealAt?.let { return it }
        val times = profile.mealTimes.sorted()
        if (times.isEmpty()) return null
        val today = times.map { now.toLocalDate().atTime(it) }.firstOrNull { !it.isBefore(now.minusMinutes(5)) }
        return today ?: now.toLocalDate().plusDays(1).atTime(times.first())
    }
    private var suggestedForMeal: LocalDateTime? = null
    var route: RouteSnapshot? = null
        private set
    var quietMode: Boolean = false
        private set
    var arrived: Boolean = false
        private set

    private val pending = mutableListOf<SeatStep>()
    private var pendingReason: String = ""
    private var suggestedForFeedDue: LocalDateTime? = null
    private var exitPrepDone = false
    private var rearReminderDone = false
    private var arrivedDestinationName: String? = null

    /** 잠들 때의 소폭 조절을 이미 했는지. 여러 번 잠들어도 쌓여서 허용 폭을 넘지 않게 여정당 한 번만 함. */
    private var sleepAdjusted = false

    val pendingSteps: List<SeatStep> get() = pending.toList()

    /** 시연을 처음부터: 도착 상태를 지워 같은 목적지로 다시 출발할 수 있게 */
    fun clearArrival() {
        arrived = false
        arrivedDestinationName = null
        route = null
    }

    fun updateProfile(p: BabyProfile) {
        profile = p
    }

    /** 다음 수유 예정 시각 (마지막 수유를 모르면 null) */
    fun nextFeedDue(): LocalDateTime? = feedDueOverride ?: lastFeedAt?.plusMinutes(profile.feedIntervalMin.toLong())

    fun minutesUntilMeal(now: LocalDateTime = clock()): Int? = nextMealAt(now)?.let { Duration.between(now, it).toMinutes().toInt() }

    fun minutesUntilFeeding(now: LocalDateTime = clock()): Int? =
        nextFeedDue()?.let { Duration.between(now, it).toMinutes().toInt() }

    fun handle(event: SessionEvent): List<CarAction> {
        val now = clock()
        return when (event) {
            is SessionEvent.PlanRequested -> {
                trip = event.trip
                val p = SeatPlanner.plan(profile, event.trip)
                plan = p
                exitPrepDone = false
                rearReminderDone = false
                sleepAdjusted = false
                arrived = false
                listOf(CarAction.ShowPlan(p))
            }

            SessionEvent.DepartureConfirmed -> {
                val p = plan ?: return listOf(CarAction.Banner("먼저 이번 여정을 알려 주세요"))
                val steps = p.sdkSteps
                val guide = p.guideSteps.joinToString { "${it.seat.labelKo} ${it.action.labelKo}" }
                buildList {
                    if (guide.isNotEmpty()) add(CarAction.Banner("직접 해 주세요: $guide"))
                    addAll(runOrQueue(steps, "출발 준비"))
                }
            }

            is SessionEvent.DrivingChanged -> {
                val prev = drivingState
                drivingState = event.state
                buildList {
                    if (event.state == DrivingState.PARKED && pending.isNotEmpty()) {
                        add(CarAction.Banner("P단을 확인했어요. $pendingReason 좌석을 맞출게요"))
                        add(CarAction.ExecuteSeatSteps(pending.toList(), pendingReason))
                        pending.clear()
                    }
                    if (event.state == DrivingState.PARKED && prev == DrivingState.DRIVING && arrived && !rearReminderDone) {
                        rearReminderDone = true
                        add(CarAction.RearSeatReminder)
                    }
                }
            }

            is SessionEvent.BabyReported -> onBaby(event.state, event.source, now)

            is SessionEvent.FeedLogged -> {
                lastFeedAt = event.at ?: now
                feedDueOverride = null
                suggestedForFeedDue = null
                if (babyState == BabyState.HUNGRY) setBaby(BabyState.AWAKE, babyStateSource, now)
                val next = nextFeedDue()!!
                listOf(CarAction.Speak("수유를 기록했어요. 다음 수유는 ${next.format(HM)}쯤이에요", quiet = quietMode))
            }

            is SessionEvent.FeedDueSet -> {
                feedDueOverride = event.at
                suggestedForFeedDue = null
                val next = nextFeedDue()
                buildList {
                    add(CarAction.Speak(if (next != null) "다음 수유는 ${next.format(HM)}쯤으로 맞췄어요" else "수유 시간은 마지막 수유를 기록하면 알려 드려요", quiet = quietMode))
                    addAll(checkFeeding(now))
                }
            }

            is SessionEvent.MealSet -> {
                mealAt = event.at
                suggestedForMeal = null
                buildList {
                    add(CarAction.Speak(event.at?.let { "밥 시간은 ${it.format(HM)}으로 맞췄어요. 그때쯤 휴게소를 찾아 드릴게요" } ?: "밥 시간은 정하지 않았어요", quiet = quietMode))
                    addAll(checkMeal(now))
                }
            }

            is SessionEvent.AssistSet -> {
                assistOn = event.on
                if (event.on) checkFeeding(now) + checkMeal(now) else emptyList()
            }

            is SessionEvent.RouteUpdated -> {
                // 도착 뒤에도 내비가 같은 경로를 계속 보낼 수 있음. 다른 목적지일 때만 새 여정으로 봄.
                if (arrived && event.route.destinationName == arrivedDestinationName) return emptyList()
                route = event.route
                arrived = false
                buildList {
                    val remainMin = event.route.remainSeconds / 60
                    if (remainMin <= EXIT_PREP_MINUTES && babyState == BabyState.ASLEEP && !exitPrepDone) {
                        exitPrepDone = true
                        addAll(exitPrep())
                    }
                    addAll(checkFeeding(now))
                    addAll(checkMeal(now))
                }
            }

            SessionEvent.DestinationArrived -> {
                arrived = true
                arrivedDestinationName = route?.destinationName
                route = null
                mealAt = null // 밥 시간은 여정마다 새로 정함
                emptyList()
            }

            SessionEvent.Tick -> checkFeeding(now) + checkMeal(now)
        }
    }

    private fun onBaby(state: BabyState, source: StateSource, now: LocalDateTime): List<CarAction> {
        val changed = state != babyState
        setBaby(state, source, now)
        val show = CarAction.ShowBabyState(state, babyStateSince, source)
        if (!changed) return listOf(show)
        return when (state) {
            BabyState.ASLEEP -> {
                quietMode = true
                suggestedForFeedDue = null // 깨면 다시 제안
                val adjusts = if (drivingState == DrivingState.DRIVING && !sleepAdjusted) sleepAdjusts() else emptyList()
                if (adjusts.isNotEmpty()) sleepAdjusted = true
                buildList {
                    add(show)
                    add(CarAction.SetQuietMode(true))
                    add(
                        CarAction.Speak(
                            if (adjusts.isEmpty()) "아기가 잠들었어요. 안내를 작게 하고 휴게소 제안은 미룰게요"
                            else "아기가 잠들었어요. 안내를 작게 하고, 잠시 뒤 ${adjusts.joinToString { it.labelKo }}",
                            quiet = true,
                        ),
                    )
                    if (adjusts.isNotEmpty()) add(CarAction.AdjustWhileDriving(adjusts, "아기가 잠들었어요"))
                    val remainMin = route?.remainSeconds?.div(60)
                    if (remainMin != null && remainMin <= EXIT_PREP_MINUTES && !exitPrepDone) {
                        exitPrepDone = true
                        addAll(exitPrep())
                    }
                }
            }
            BabyState.AWAKE, BabyState.DROWSY -> {
                val wasQuiet = quietMode
                quietMode = false
                buildList {
                    add(show)
                    if (wasQuiet) add(CarAction.SetQuietMode(false))
                    addAll(checkFeeding(now))
                }
            }
            BabyState.CRYING -> {
                quietMode = false
                listOf(
                    show,
                    CarAction.SetQuietMode(false),
                    CarAction.Speak("아기가 울고 있어요. 가까운 휴게소를 찾아볼게요"),
                    CarAction.SuggestNearestStop,
                )
            }
            BabyState.HUNGRY -> {
                quietMode = false
                listOf(
                    show,
                    CarAction.SetQuietMode(false),
                    CarAction.Speak("아기가 배고파해요. 수유실 있는 휴게소를 찾아볼게요"),
                    CarAction.SuggestNursingStops(0),
                )
            }
            BabyState.UNKNOWN -> listOf(show)
        }
    }

    /** 잠들었을 때 소폭 조절: 보호자 자리 + (허용 폭을 입력했을 때만) 카시트 좌석 */
    private fun sleepAdjusts(): List<SmallAdjust> {
        val p = plan ?: return emptyList()
        return buildList {
            p.assignments.entries.firstOrNull { it.value == Occupant.CAREGIVER }?.key?.let { seat ->
                add(SmallAdjust(seat, SeatAxis.BACKREST, towardPositive = false, "보호자 자리 등받이 조금 눕힘", CAREGIVER_ADJUST_PERCENT))
            }
            if (profile.carSeatReclineAllowance > 0) {
                p.carSeatSeats.forEach { seat ->
                    add(SmallAdjust(seat, SeatAxis.BACKREST, towardPositive = false, "카시트 좌석 허용 각도 안에서 조금 눕힘", profile.carSeatReclineAllowance))
                }
            }
        }
    }

    /** 보호자가 고른 "안내 소리 작게" (휴대폰 · 카메라 [적용]) */
    fun quietOn() {
        quietMode = true
    }

    /** 화면의 [되돌리기]: 안내 음성을 원래대로 (아기 상태는 그대로) */
    fun clearQuiet() {
        quietMode = false
        seatsRestored()
    }

    /** 소폭 조절을 되돌렸으니 다음에 잠들면 다시 조절해도 허용 폭 안임 */
    fun seatsRestored() {
        sleepAdjusted = false
    }

    private fun setBaby(state: BabyState, source: StateSource, now: LocalDateTime) {
        if (state != babyState) babyStateSince = now
        babyState = state
        babyStateSource = source
    }

    /**
     * 수유 30분 전, 남은 주행이 그보다 길면 수유실 휴게소를 찾아 제안 (차 화면 알림으로 뜸).
     * 자는 중이어도 미리 찾아 알림은 띄움 (음성은 작게).
     */
    private fun checkFeeding(now: LocalDateTime): List<CarAction> {
        if (!assistOn) return emptyList()
        if (drivingState != DrivingState.DRIVING) return emptyList()
        val due = nextFeedDue() ?: return emptyList()
        if (suggestedForFeedDue == due) return emptyList()
        val untilMin = Duration.between(now, due).toMinutes().toInt()
        if (untilMin > FEED_LEAD_MINUTES) return emptyList()
        val remainMin = route?.remainSeconds?.div(60)?.toInt() ?: return emptyList()
        if (remainMin <= untilMin.coerceAtLeast(0) + 10) return emptyList() // 곧 도착하면 도착해서 수유
        suggestedForFeedDue = due
        val text = if (untilMin > 0) "${untilMin}분 뒤 수유 시간이에요. 수유실 있는 휴게소를 경유할까요?"
        else "수유 시간이 지났어요. 수유실 있는 휴게소를 경유할까요?"
        return listOf(CarAction.Speak(text, quiet = quietMode), CarAction.SuggestNursingStops(untilMin.coerceAtLeast(0)))
    }

    /**
     * 밥 시간 20분 전, 남은 주행이 그보다 길면 휴게소를 찾아 제안 (차 화면 알림). 정한 밥 시간마다 한 번만.
     */
    private fun checkMeal(now: LocalDateTime): List<CarAction> {
        if (!assistOn) return emptyList()
        if (drivingState != DrivingState.DRIVING) return emptyList()
        val at = nextMealAt(now) ?: return emptyList()
        if (suggestedForMeal == at) return emptyList()
        val untilMin = Duration.between(now, at).toMinutes().toInt()
        if (untilMin > MEAL_LEAD_MINUTES) return emptyList()
        val remainMin = route?.remainSeconds?.div(60)?.toInt() ?: return emptyList()
        if (remainMin <= untilMin.coerceAtLeast(0) + 10) return emptyList() // 곧 도착하면 도착해서 먹음
        suggestedForMeal = at
        val text = if (untilMin > 0) "${untilMin}분 뒤 밥 시간이에요. 휴게소를 경유할까요?" else "밥 시간이에요. 휴게소를 경유할까요?"
        return listOf(CarAction.Speak(text, quiet = quietMode), CarAction.SuggestMealStops(untilMin.coerceAtLeast(0)))
    }

    /** 잠든 아기를 깨우지 않고 내리기: 카시트 앞좌석(비어 있으면)을 앞으로 → 정차 후 실행 */
    private fun exitPrep(): List<CarAction> {
        val p = plan ?: return emptyList()
        val steps = p.carSeatSeats.mapNotNull { carSeat ->
            val front = carSeat.seatInFront() ?: return@mapNotNull null
            if (front.isDriver) return@mapNotNull null
            if (p.assignments[front] != null && p.assignments[front] != Occupant.DRIVER) return@mapNotNull null
            SeatStep(front, SeatAction.Move(SeatAxis.FORE_AFT, 1f), Executor.SDK_CONTROL, "잠든 아기를 안고 내릴 공간 확보")
        }
        if (steps.isEmpty()) {
            return listOf(CarAction.Speak("곧 도착해요. 아기가 자고 있어요", quiet = true))
        }
        return buildList {
            add(CarAction.Speak("곧 도착해요. 정차하면 ${steps.joinToString { it.seat.labelKo }}을 앞으로 옮겨 내릴 공간을 만들게요", quiet = true))
            addAll(runOrQueue(steps, "편한 하차"))
        }
    }

    private fun runOrQueue(steps: List<SeatStep>, reason: String): List<CarAction> {
        if (steps.isEmpty()) return emptyList()
        return if (drivingState == DrivingState.PARKED) {
            listOf(CarAction.ExecuteSeatSteps(steps, reason))
        } else {
            pending.removeAll { s -> steps.any { it.seat == s.seat && it.action::class == s.action::class && sameAxis(it, s) } }
            pending += steps
            pendingReason = reason
            val why = if (drivingState == DrivingState.DRIVING) "주행 중이라" else "정차 상태를 아직 확인하지 못해서"
            listOf(CarAction.QueueSeatSteps(steps, reason), CarAction.Banner("$why P단이 확인되면 $reason 좌석을 맞출게요"))
        }
    }

    private fun sameAxis(a: SeatStep, b: SeatStep): Boolean {
        val ma = a.action as? SeatAction.Move
        val mb = b.action as? SeatAction.Move
        return ma == null || mb == null || ma.axis == mb.axis
    }

    companion object {
        const val FEED_LEAD_MINUTES = 30
        const val MEAL_LEAD_MINUTES = 20
        const val MAX_DEFER_WHILE_ASLEEP = 30
        const val EXIT_PREP_MINUTES = 5L
        const val CAREGIVER_ADJUST_PERCENT = 10
        private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

        /** 카시트를 단 좌석 (계획이 없으면 기본 2열 오른쪽) */
        fun carSeatSeat(plan: SeatPlan?): SeatId = plan?.carSeatSeats?.firstOrNull() ?: SeatId.R2_RIGHT
    }
}
