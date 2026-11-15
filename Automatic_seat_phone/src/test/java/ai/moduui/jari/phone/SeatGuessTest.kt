package ai.moduui.jari.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatGuessTest {
    private val riders = listOf(
        Rider("운전자", Who.ADULT, null, driver = true),
        Rider("이봄", Who.BABY, SeatNeed.REAR_SEAT),
        Rider("어머니", Who.SENIOR, null),
        Rider("삼촌", Who.ADULT, null, note = "잘 졸아요"),
    )
    private val plan = mapOf("R1_LEFT" to "운전자", "R1_RIGHT" to "어머니", "R2_RIGHT" to "이봄", "R2_LEFT" to "삼촌")

    @Test
    fun rulesWhenNoMemory() {
        val g = SeatGuessRules.guess(plan, riders, emptyList())
        assertFalse("R1_LEFT" in g)
        assertEquals(SeatGuessRules.CHILD_SEAT_DEG, backrestDeg(g.getValue("R2_RIGHT").backrest))
        assertEquals(SeatGuessRules.SLEEP_DEG, backrestDeg(g.getValue("R2_LEFT").backrest))
        // 어르신 조수석: 조금 세움 + 뒤 후방 카시트 공간 때문에 앞으로
        val mom = g.getValue("R1_RIGHT")
        assertEquals(SeatGuessRules.SENIOR_DEG, backrestDeg(mom.backrest))
        assertEquals(SeatGuessRules.ROOM_FOR_REAR_SEAT, mom.foreAft)
        assertTrue(mom.why, mom.why.contains("카시트 공간"))
    }

    @Test
    fun memoryBeatsRules() {
        val mem = listOf(
            SeatRecord(1, "R2_RIGHT", "이봄", "", 30, 60, "내리기 전"),
            SeatRecord(2, "R2_RIGHT", "이봄", "", 40, 55, "내리기 전"),
            SeatRecord(3, "R2_RIGHT", "삼촌", "", 50, 40, "직접 조절"),
        )
        val g = SeatGuessRules.guess(plan, riders, mem)
        assertEquals(55, g.getValue("R2_RIGHT").backrest)
        assertEquals(40, g.getValue("R2_RIGHT").foreAft)
        assertEquals("평소 2번", g.getValue("R2_RIGHT").why)
        // 삼촌은 다른 자리 기억의 등받이만 가져옴
        assertEquals(40, g.getValue("R2_LEFT").backrest)
        assertEquals(null, g.getValue("R2_LEFT").foreAft)
    }
}
