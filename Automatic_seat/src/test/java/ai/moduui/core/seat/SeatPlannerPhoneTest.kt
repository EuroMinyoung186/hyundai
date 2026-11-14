package ai.moduui.core.seat

import ai.moduui.core.model.BabyProfile
import ai.moduui.core.model.CarSeatDirection
import ai.moduui.core.model.PlannedKind
import ai.moduui.core.model.PlannedSeat
import ai.moduui.core.model.SeatId
import ai.moduui.core.model.TripRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 휴대폰 2D 배치(seatPlan)를 따르는지 */
class SeatPlannerPhoneTest {
    private val forward = BabyProfile(seatDirection = CarSeatDirection.FORWARD_FACING)

    private fun moves(plan: SeatPlan, seat: SeatId, axis: SeatAxis) =
        plan.steps.filter { it.seat == seat && (it.action as? SeatAction.Move)?.axis == axis }.map { (it.action as SeatAction.Move).targetFraction }

    @Test
    fun followsPhoneSeats() {
        val trip = TripRequest(
            seniors = 2, adults = 0, infants = 1,
            seatPlan = listOf(
                PlannedSeat(SeatId.R1_RIGHT, "어르신", PlannedKind.SENIOR),
                PlannedSeat(SeatId.R2_LEFT, "아기", PlannedKind.CAR_SEAT),
                PlannedSeat(SeatId.R2_RIGHT, "어르신2", PlannedKind.SENIOR),
            ),
        )
        val p = SeatPlanner.plan(forward, trip)
        assertEquals(Occupant.SENIOR, p.assignments[SeatId.R1_RIGHT])
        assertEquals(Occupant.INFANT_CAR_SEAT, p.assignments[SeatId.R2_LEFT])
        // 아기 옆 같은 줄 어른 → 보호자
        assertEquals(Occupant.CAREGIVER, p.assignments[SeatId.R2_RIGHT])
    }

    @Test
    fun carSeatInFrontRowIsRejected() {
        val trip = TripRequest(infants = 1, adults = 0, seatPlan = listOf(PlannedSeat(SeatId.R1_RIGHT, "아기", PlannedKind.CAR_SEAT)))
        val p = SeatPlanner.plan(forward, trip)
        assertFalse(SeatId.R1_RIGHT in p.assignments)
        assertTrue(p.notesKo.any { "앞좌석" in it })
    }

    @Test
    fun phonePostureOnlyOnAxesRulesDidNotSet() {
        val trip = TripRequest(
            infants = 1, seniors = 1, adults = 0,
            seatPlan = listOf(
                PlannedSeat(SeatId.R2_RIGHT, "아기", PlannedKind.CAR_SEAT, foreAft = 80, backrest = 40),
                PlannedSeat(SeatId.R2_LEFT, "어르신", PlannedKind.SENIOR, foreAft = 65, backrest = 89),
            ),
        )
        val p = SeatPlanner.plan(forward, trip)
        // 카시트 자리는 규칙대로 (뒤로 0, 등받이 세움 1) — 휴대폰 값으로 바꾸지 않음
        assertEquals(listOf(0f), moves(p, SeatId.R2_RIGHT, SeatAxis.FORE_AFT))
        assertEquals(listOf(1f), moves(p, SeatId.R2_RIGHT, SeatAxis.BACKREST))
        // 보호자 자리는 휴대폰 자세
        assertEquals(listOf(0.65f), moves(p, SeatId.R2_LEFT, SeatAxis.FORE_AFT))
        assertEquals(listOf(0.89f), moves(p, SeatId.R2_LEFT, SeatAxis.BACKREST))
    }

    @Test
    fun phonePostureIsClampedToRideLimits() {
        val trip = TripRequest(
            adults = 2, infants = 0,
            seatPlan = listOf(
                PlannedSeat(SeatId.R1_RIGHT, "엄마", PlannedKind.ADULT, foreAft = 100, backrest = 0),
                PlannedSeat(SeatId.R2_LEFT, "아빠", PlannedKind.ADULT, foreAft = 50, backrest = 0),
                PlannedSeat(SeatId.R1_LEFT, "운전자", PlannedKind.ADULT, foreAft = 0, backrest = 0),
            ),
        )
        val p = SeatPlanner.plan(forward, trip)
        assertEquals(listOf(0.75f), moves(p, SeatId.R1_RIGHT, SeatAxis.FORE_AFT))
        assertEquals(listOf(0.57f), moves(p, SeatId.R1_RIGHT, SeatAxis.BACKREST))
        assertEquals(listOf(0.43f), moves(p, SeatId.R2_LEFT, SeatAxis.BACKREST))
        // 운전석은 휴대폰 값이 와도 움직이지 않음
        assertTrue(p.steps.none { it.seat == SeatId.R1_LEFT })
    }

    @Test
    fun withoutPhonePlanRulesStillPlace() {
        val p = SeatPlanner.plan(forward, TripRequest(infants = 1, adults = 1))
        assertEquals(Occupant.INFANT_CAR_SEAT, p.assignments[SeatId.R2_RIGHT])
        assertEquals(Occupant.CAREGIVER, p.assignments[SeatId.R2_LEFT])
    }
}
