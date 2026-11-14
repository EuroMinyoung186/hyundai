package ai.moduui.core.session

import ai.moduui.core.model.BabyProfile
import ai.moduui.core.model.DrivingState
import ai.moduui.core.model.RouteSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 출발 알림에서 정한 다음 수유 · 밥 시간 */
class FeedMealTest {
    private var now = LocalDateTime.of(2026, 11, 14, 12, 0)
    private val s = TripSession(BabyProfile(feedIntervalMin = 180)) { now }

    private fun drive(remainMin: Long = 180) {
        s.handle(SessionEvent.DrivingChanged(DrivingState.DRIVING))
        s.handle(SessionEvent.RouteUpdated(RouteSnapshot("강릉", null, remainMin * 60, null, now)))
    }

    @Test
    fun feedDueOverrideWinsUntilFeedLogged() {
        s.handle(SessionEvent.FeedLogged(now.minusMinutes(30)))
        assertEquals(now.plusMinutes(150), s.nextFeedDue())
        s.handle(SessionEvent.FeedDueSet(now.plusMinutes(60)))
        assertEquals(now.plusMinutes(60), s.nextFeedDue())
        // 수유를 기록하면 다시 간격으로
        s.handle(SessionEvent.FeedLogged(now))
        assertEquals(now.plusMinutes(180), s.nextFeedDue())
    }

    @Test
    fun feedSoonSuggestsNursingStop() {
        drive()
        val actions = s.handle(SessionEvent.FeedDueSet(now.plusMinutes(30)))
        assertTrue(actions.any { it is CarAction.SuggestNursingStops })
    }

    @Test
    fun mealSuggestsStopTwentyMinutesBefore() {
        drive()
        assertTrue(s.handle(SessionEvent.MealSet(now.plusMinutes(60))).none { it is CarAction.SuggestMealStops })
        now = now.plusMinutes(41)
        val a = s.handle(SessionEvent.Tick)
        assertTrue(a.any { it is CarAction.SuggestMealStops })
        // 같은 밥 시간에는 한 번만
        assertTrue(s.handle(SessionEvent.Tick).none { it is CarAction.SuggestMealStops })
    }

    @Test
    fun mealNotSuggestedWhenArrivingSoon() {
        // 15분 뒤 도착 ≤ 밥 10분 뒤 + 10분 → 도착해서 먹음
        drive(remainMin = 15)
        assertTrue(s.handle(SessionEvent.MealSet(now.plusMinutes(10))).none { it is CarAction.SuggestMealStops })
    }

    @Test
    fun mealClearedOnArrival() {
        drive()
        s.handle(SessionEvent.MealSet(now.plusMinutes(90)))
        s.handle(SessionEvent.DestinationArrived)
        assertNull(s.mealAt)
    }
}
