package ai.moduui.jari.control

import ai.moduui.core.model.SeatId
import ai.moduui.core.seat.SeatAxis
import ai.moduui.core.seat.SeatPlanner
import ai.moduui.core.seat.TrunkEstimate
import ai.moduui.core.seat.TrunkEstimator
import ai.moduui.jari.vehicle.SeatFeature
import ai.moduui.jari.vehicle.Support

/**
 * 좌석 하나의 지금 상황. 휴대폰 상태 JSON 의 seats[] 와 차 3D 화면이 같은 값을 씀.
 * 위치는 0..100 (앞뒤 100 = 맨 앞, 등받이 100 = 가장 세움). 모르면 null.
 */
data class SeatView(
    val id: SeatId,
    val foreAftMove: Boolean,
    val backrestMove: Boolean,
    val foreAft: Int?,
    val backrest: Int?,
    val occupied: Boolean?,
) {
    val movable: Boolean get() = foreAftMove || backrestMove
}

private fun known(s: Support) = s == Support.READ_WRITE || s == Support.READ_ONLY

/**
 * 이 차에 있는 좌석 (지원 목록에 읽기 · 쓰기가 하나라도 있는 좌석). 목록을 아직 못 받았으면 2열 4석.
 * 위치는 실차 SDK(getCurrentSeat*Position) 또는 에뮬레이터 콘솔에서 읽은 값.
 */
fun seatViews(ui: UiState): List<SeatView> {
    val v = ui.vehicle
    val present = SeatId.entries.filter { id -> v.support[id]?.values?.any(::known) == true }
        .ifEmpty { listOf(SeatId.R1_LEFT, SeatId.R1_RIGHT, SeatId.R2_LEFT, SeatId.R2_RIGHT) }
    return present.map { id ->
        SeatView(
            id = id,
            foreAftMove = v.support(id, SeatFeature.FORE_AFT_MOVE) == Support.READ_WRITE,
            backrestMove = v.support(id, SeatFeature.BACKREST_MOVE) == Support.READ_WRITE,
            foreAft = v.positions[id to SeatAxis.FORE_AFT],
            backrest = v.positions[id to SeatAxis.BACKREST],
            occupied = v.occupancy[id],
        )
    }
}

/**
 * 트렁크 어림값. SDK 에 트렁크 API 가 없어서 좌석 줄 수 · 마지막 줄 좌석 위치 ·
 * 좌석 계획의 3열 접힘 · 짐 부피로 어림함 (TrunkEstimator).
 */
fun trunkEstimate(ui: UiState): TrunkEstimate {
    val v = ui.vehicle
    val rows = SeatId.entries.filter { id -> v.support[id]?.values?.any(::known) == true }.maxOfOrNull { it.row } ?: 2
    val used = ui.plan?.luggageLiters ?: ui.trip?.let { SeatPlanner.luggageLiters(it) } ?: 0
    val foreAft = SeatId.entries.mapNotNull { id -> v.positions[id to SeatAxis.FORE_AFT]?.let { id to it } }.toMap()
    return TrunkEstimator.estimate(rows, ui.plan?.trunkMode, foreAft, used)
}
