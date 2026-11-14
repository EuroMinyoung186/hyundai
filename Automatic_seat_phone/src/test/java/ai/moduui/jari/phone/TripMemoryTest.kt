package ai.moduui.jari.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripMemoryTest {
    private fun rec(at: Long, seat: String, who: String) = SeatRecord(at, seat, who, "", 40, 75, "내리기 전")

    private fun ride(at: Long, seats: Map<String, String>, liters: Int, dest: String = "", km: Double? = null) =
        RideRecord(at, dest, km, 30, TripDraft(), liters, seats, emptyMap())

    private val riders = listOf(
        Rider("운전자", Who.ADULT, null, driver = true),
        Rider("이봄", Who.BABY, SeatNeed.REAR_SEAT),
        Rider("어머니", Who.SENIOR, null),
    )
    private val seats = listOf("R1_LEFT", "R1_RIGHT", "R2_LEFT", "R2_RIGHT")

    @Test
    fun habitsArePerPersonMostUsedFirst() {
        val h = personHabits(
            listOf(rec(1, "R2_RIGHT", "이봄"), rec(2, "R2_RIGHT", "이봄"), rec(3, "R2_LEFT", "이봄"), rec(4, "R1_RIGHT", "어머니"), rec(5, "R1_LEFT", "운전자")),
        )
        assertEquals(setOf("이봄", "어머니"), h.map { it.who }.toSet())
        val bom = h.first { it.who == "이봄" }
        assertEquals("R2_RIGHT", bom.top?.seat)
        assertEquals(3, bom.total)
    }

    @Test
    fun similarRideNeedsSamePeopleAndLuggage() {
        val same = ride(1, mapOf("R1_LEFT" to "운전자", "R2_LEFT" to "이봄", "R2_RIGHT" to "어머니"), 200, "시댁", 40.0)
        val other = ride(2, mapOf("R1_LEFT" to "운전자", "R1_RIGHT" to "친구"), 0, "회사", 5.0)
        val q = RideQuery(listOf("이봄", "어머니"), 220, "시댁", 42.0)
        val found = RideMatch.similar(q, listOf(other, same))
        assertEquals(1, found.size)
        assertEquals(same, found[0].ride)
        assertTrue(found[0].score >= 90)
        assertTrue("같은 목적지" in found[0].why)
    }

    @Test
    fun unnamedKidsMatchByKind() {
        assertEquals(1.0, RideMatch.riderOverlap(listOf("아이1", "아이2"), listOf("아이", "아이")).first, 0.001)
    }

    @Test
    fun rulePlanUsesUsualSeatsButNeverBreaksSafety() {
        // 이봄 평소 2열 왼쪽, 어머니 평소 2열 오른쪽
        val mem = PlanMemory(personHabits(listOf(rec(1, "R2_LEFT", "이봄"), rec(2, "R2_RIGHT", "어머니"))))
        val plan = PlanRules.fallback(riders, seats, mem.prefer(riders))
        assertEquals("이봄", plan["R2_LEFT"])
        assertEquals("어머니", plan["R2_RIGHT"])
        assertEquals(2 to 2, mem.match(plan, riders))
        // 기억에 조수석이 있어도 아기는 앞에 앉히지 않음
        val bad = PlanMemory(personHabits(listOf(rec(1, "R1_RIGHT", "이봄"))))
        val p2 = PlanRules.fallback(riders, seats, bad.prefer(riders))
        assertTrue(p2.entries.first { it.value == "이봄" }.key.startsWith("R2"))
    }

    @Test
    fun verySimilarRideComesFirst() {
        val past = ride(1, mapOf("R1_LEFT" to "운전자", "R2_LEFT" to "어머니", "R2_RIGHT" to "이봄"), 0)
        val mem = PlanMemory(
            personHabits(listOf(rec(1, "R1_RIGHT", "어머니"))),
            RideMatch.similar(RideQuery(listOf("이봄", "어머니"), 0), listOf(past)),
        )
        assertEquals(listOf("R2_LEFT", "R1_RIGHT"), mem.prefer(riders)["어머니"])
        val prompt = PlanPrompt.build(riders, seats, mem)
        assertTrue(prompt, prompt.contains("[평소: R2_LEFT, R1_RIGHT]"))
        assertTrue(prompt, prompt.contains("비슷한 지난 여정"))
    }
}
