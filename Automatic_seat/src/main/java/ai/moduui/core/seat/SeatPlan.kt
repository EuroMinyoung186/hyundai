package ai.moduui.core.seat

import ai.moduui.core.model.SeatId

/** 좌석 이동 축. AAOS 규약을 따름: 값이 클수록 앞쪽(FORE_AFT) / 곧게 세움(BACKREST). */
enum class SeatAxis(val labelKo: String) {
    FORE_AFT("앞뒤"),
    BACKREST("등받이"),
}

/**
 * 누가 실행하는지.
 * - SDK_CONTROL: Pleos Vehicle SDK 제어 API로 실제 실행 (Vehicle Control 권한 필요, 정차 중에만)
 * - GUIDE: 공개 API가 없어 화면·음성 안내만 함 (3열 접기 등). 화면에 "직접" 배지.
 */
enum class Executor(val badgeKo: String) {
    SDK_CONTROL("실제"),
    GUIDE("직접"),
}

sealed interface SeatAction {
    val labelKo: String

    /** 목표 위치를 capability 범위의 비율로 표현 (0.0 = min, 1.0 = max) */
    data class Move(val axis: SeatAxis, val targetFraction: Float) : SeatAction {
        init {
            require(targetFraction in 0f..1f) { "targetFraction must be 0..1" }
        }

        override val labelKo: String
            get() = when (axis) {
                SeatAxis.FORE_AFT -> if (targetFraction >= 0.5f) "앞으로 ${(targetFraction * 100).toInt()}%" else "뒤로 (${(targetFraction * 100).toInt()}%)"
                SeatAxis.BACKREST -> if (targetFraction >= 0.8f) "등받이 세우기" else "등받이 ${(targetFraction * 100).toInt()}%"
            }
    }

    data class Heat(val level: Int) : SeatAction {
        override val labelKo: String get() = if (level == 0) "열선 끄기" else "열선 ${level}단"
    }

    data class Vent(val level: Int) : SeatAction {
        override val labelKo: String get() = if (level == 0) "통풍 끄기" else "통풍 ${level}단"
    }

    data object Fold : SeatAction {
        override val labelKo: String get() = "접기"
    }

    data object Unfold : SeatAction {
        override val labelKo: String get() = "펴기"
    }
}

data class SeatStep(
    val seat: SeatId,
    val action: SeatAction,
    val executor: Executor,
    val reasonKo: String,
)

enum class TrunkMode(val labelKo: String, val usableLiters: Int) {
    ALL_UP("3열 모두 펼침", 500),
    R3_RIGHT_FOLDED("3열 오른쪽 접음", 850),
    R3_ALL_FOLDED("3열 모두 접음", 1200),
}

enum class Occupant(val labelKo: String) {
    DRIVER("운전자"),
    SENIOR("어르신"),
    ADULT("어른"),
    CAREGIVER("보호자"),
    CHILD_BOOSTER("아이(부스터)"),
    INFANT_CAR_SEAT("아기(카시트)"),
}

data class SeatPlan(
    val assignments: Map<SeatId, Occupant>,
    val trunkMode: TrunkMode,
    val luggageLiters: Int,
    val overflowLiters: Int,
    val steps: List<SeatStep>,
    val notesKo: List<String>,
) {
    val carSeatSeats: List<SeatId>
        get() = assignments.filterValues { it == Occupant.INFANT_CAR_SEAT }.keys.sortedBy { it.ordinal }

    val sdkSteps: List<SeatStep> get() = steps.filter { it.executor == Executor.SDK_CONTROL }
    val guideSteps: List<SeatStep> get() = steps.filter { it.executor == Executor.GUIDE }

    /** 홈 화면 한 줄 요약 */
    val summaryKo: String
        get() = buildString {
            append("짐 ${luggageLiters}L → ${trunkMode.labelKo}")
            if (carSeatSeats.isNotEmpty()) append(", 카시트 ").append(carSeatSeats.joinToString { it.labelKo })
            val caregiver = assignments.entries.firstOrNull { it.value == Occupant.CAREGIVER }?.key
            if (caregiver != null) append(", 보호자 ").append(caregiver.labelKo)
        }
}
