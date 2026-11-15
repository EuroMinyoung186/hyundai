package ai.moduui.core.session

import ai.moduui.core.model.BabyProfile
import ai.moduui.core.model.DrivingState
import ai.moduui.core.model.RouteSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/** 설정의 아기 밥 시간 · 출발 알림 [아니요] (11/15) */
class MealTimesTest {
    private var now = LocalDateTime.of(2026, 11, 15, 11, 30)
    private val s = TripSession(
        BabyProfile(feedIntervalMin = 180, mealTimes = listOf(LocalTime.of(12, 0), LocalTime.of(18, 0))),
    ) { now }

    private fun drive() {
        s.handle(SessionEvent.DrivingChanged(DrivingState.DRIVING))
        s.handle(SessionEvent.RouteUpdated(RouteSnapshot("강릉", null, 180 * 60, null, now)))
    }

    @Test
    fun nextMealComesFromSettings() {
        assertEquals(now.withHour(12).withMinute(0), s.nextMealAt())
        now = LocalDateTime.of(2026, 11, 15, 13, 0)
        assertEquals(now.withHour(18).withMinute(0), s.nextMealAt())
        // 저녁도 지나면 다음 날 첫 끼니
        now = LocalDateTime.of(2026, 11, 15, 20, 0)
        assertEquals(LocalDateTime.of(2026, 11, 16, 12, 0), s.nextMealAt())
    }

    @Test
    fun noMealTimesMeansNoMeal() {
        val t = TripSession(BabyProfile()) { now }
        assertNull(t.nextMealAt())
    }

    @Test
    fun settingsMealSuggestsStop() {
        drive()
        now = now.plusMinutes(11) // 밥 19분 전
        assertTrue(s.handle(SessionEvent.Tick).any { it is CarAction.SuggestMealStops })
    }

    @Test
    fun answeringNoStopsSuggestions() {
        drive()
        s.handle(SessionEvent.AssistSet(false))
        s.handle(SessionEvent.FeedLogged(now.minusMinutes(170)))
        now = now.plusMinutes(11)
        val a = s.handle(SessionEvent.Tick)
        assertTrue(a.none { it is CarAction.SuggestMealStops || it is CarAction.SuggestNursingStops })
        // 다시 켜면 제안
        assertTrue(s.handle(SessionEvent.AssistSet(true)).any { it is CarAction.SuggestMealStops || it is CarAction.SuggestNursingStops })
    }
}
