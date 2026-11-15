package ai.moduui.core.seat

import ai.moduui.core.model.SeatId
import kotlin.math.roundToInt

/**
 * 트렁크 공간 어림값. Vehicle SDK 에는 트렁크 용량 · 짐 감지 · 좌석 접힘 API 가 없어서 재지 못하고 어림함.
 *
 * @param liters 지금 쓸 수 있는 트렁크 용량(L)
 * @param usedLiters 이번 여정 짐에 필요한 부피(L, 쌓을 때 생기는 빈틈 포함)
 * @param rows 이 차 좌석 줄 수 (SDK 가 지원한다고 답한 좌석으로 셈)
 * @param folded 짐 때문에 접는 좌석 (좌석 계획 기준)
 * @param slideLiters 마지막 줄 좌석 앞뒤 위치로 늘거나 준 양(L). 위치를 모르면 0
 * @param basisKo 어디서 나온 값인지 한 줄
 */
data class TrunkEstimate(
    val liters: Int,
    val usedLiters: Int,
    val rows: Int,
    val folded: List<SeatId>,
    val slideLiters: Int,
    val basisKo: String,
) {
    val overflowLiters: Int get() = (usedLiters - liters).coerceAtLeast(0)
}

object TrunkEstimator {
    /** 차종을 모를 때 2열 차 트렁크 기본값 (중형 SUV, 2열 가운데 위치 기준) */
    const val TWO_ROW_BASE_LITERS = 600

    /** 마지막 줄 좌석을 맨 뒤에서 맨 앞까지 옮기면 달라지는 양: 약 25cm × 트렁크 단면 0.8㎡ */
    const val SLIDE_SPAN_LITERS = 200

    /**
     * @param rows 좌석 줄 수 (2 또는 3)
     * @param mode 좌석 계획이 고른 3열 접힘 (2열 차는 무시)
     * @param foreAft 좌석별 앞뒤 위치 0..100 (100 = 맨 앞). SDK · 콘솔에서 읽은 값만
     * @param usedLiters 짐 부피 (SeatPlanner.luggageLiters)
     */
    fun estimate(rows: Int, mode: TrunkMode?, foreAft: Map<SeatId, Int>, usedLiters: Int): TrunkEstimate {
        val threeRow = rows >= 3
        val m = mode ?: TrunkMode.ALL_UP
        val base = if (threeRow) m.usableLiters else TWO_ROW_BASE_LITERS
        val folded = when {
            !threeRow -> emptyList()
            m == TrunkMode.R3_RIGHT_FOLDED -> listOf(SeatId.R3_RIGHT)
            m == TrunkMode.R3_ALL_FOLDED -> listOf(SeatId.R3_LEFT, SeatId.R3_RIGHT)
            else -> emptyList()
        }
        // 트렁크 바로 앞 줄: 3열을 다 접으면 2열, 아니면 마지막 줄. 한쪽만 접으면 펼친 쪽 좌석만 봄
        val boundRow = if (threeRow && m == TrunkMode.R3_ALL_FOLDED) 2 else if (threeRow) 3 else 2
        val bound = SeatId.entries.filter { it.row == boundRow && it !in folded }.mapNotNull { foreAft[it] }
        val slide = if (bound.isEmpty()) 0 else ((bound.average() - 50) / 100 * SLIDE_SPAN_LITERS).roundToInt()
        val basis = buildString {
            append(if (threeRow) "7인승 기본값 (${m.labelKo})" else "2열 차 기본값")
            append(if (bound.isEmpty()) " · 좌석 위치 모름" else " · ${boundRow}열 좌석 위치 반영")
        }
        return TrunkEstimate(base + slide, usedLiters, if (threeRow) 3 else 2, folded, slide, basis)
    }
}
