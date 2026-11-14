package ai.moduui.core.session

import ai.moduui.core.model.BabyState
import ai.moduui.core.model.SeatId
import ai.moduui.core.model.StateSource
import ai.moduui.core.seat.SeatAxis
import ai.moduui.core.seat.SeatPlan
import ai.moduui.core.seat.SeatStep
import java.time.LocalDateTime

/**
 * 주행 중 작은 좌석 조절 한 건.
 * @param towardPositive true = 앞으로 · 곧게, false = 뒤로 · 눕힘 (SeatAxis 규약)
 * @param maxPercent 좌석 범위에서 움직여도 되는 최대 폭 (%)
 */
data class SmallAdjust(
    val seat: SeatId,
    val axis: SeatAxis,
    val towardPositive: Boolean,
    val labelKo: String,
    val maxPercent: Int,
)

/**
 * TripSession 이 내놓는 "해야 할 일". 실제 실행은 앱의 ActionExecutor 가 SDK·화면·음성으로 바꿈.
 * core 는 안드로이드를 모르므로 여기서는 의도만 표현함.
 */
sealed interface CarAction {

    /** 앱 음성 안내. quiet = 아기가 자는 중이라 작게. */
    data class Speak(val textKo: String, val quiet: Boolean = false) : CarAction

    /** 화면 배너 (주행 중에는 한 줄, 큰 글씨) */
    data class Banner(val textKo: String) : CarAction

    data class ShowPlan(val plan: SeatPlan) : CarAction

    data class ShowBabyState(val state: BabyState, val since: LocalDateTime, val source: StateSource) : CarAction

    /** 앱 안내 음성을 작게 + 휴게소 제안 미룸 */
    data class SetQuietMode(val on: Boolean) : CarAction

    /** 수유실 휴게소 후보를 찾아 화면(C-4)과 음성으로 제안 */
    data class SuggestNursingStops(val minutesUntilFeeding: Int) : CarAction

    /** 울음: 가장 가까운 휴게소 안내 (수유실 여부 무관) */
    data object SuggestNearestStop : CarAction

    /** 밥 시간 전: 경로 위 휴게소 제안 */
    data class SuggestMealStops(val minutesUntilMeal: Int) : CarAction

    /** 정차(P) 상태에서 좌석 단계 실행. 실행 전 카운트다운으로 취소 기회를 줌. */
    data class ExecuteSeatSteps(val steps: List<SeatStep>, val reasonKo: String, val countdownSeconds: Int = 5) : CarAction

    /** 지금은 실행할 수 없어(주행 중) 정차 후 실행하려고 대기열에 넣음 */
    data class QueueSeatSteps(val steps: List<SeatStep>, val reasonKo: String) : CarAction

    /** 주행 중 소폭 조절: 먼저 알린 뒤 짧게 움직이고 [되돌리기]를 줌 */
    data class AdjustWhileDriving(val adjusts: List<SmallAdjust>, val reasonKo: String) : CarAction

    /** 도착 후 뒷좌석 확인 알림 */
    data object RearSeatReminder : CarAction
}
