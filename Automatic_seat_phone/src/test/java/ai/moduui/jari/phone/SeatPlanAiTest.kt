package ai.moduui.jari.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatPlanAiTest {
    private val seats = listOf("R1_LEFT", "R1_RIGHT", "R2_LEFT", "R2_RIGHT")
    private val bom = PresetParser.personFrom("이봄", "10개월", "", "", "", 1)!!
    private val jimin = PresetParser.personFrom("지민", "5", "110", "", "차멀미", 2)!!
    private val mom = PresetParser.personFrom("어머니", "", "", "", "허리 불편", 3)!!
    private val riders = ridersOf(TripDraft(seniors = 1, children = 1, infants = 1), listOf(1L, 2L, 3L), listOf(bom, jimin, mom))

    @Test
    fun ridersUseNames() {
        assertEquals(listOf("운전자", "어머니", "지민", "이봄"), riders.map { it.label })
    }

    @Test
    fun parseLlmLines() {
        val text = "R1_RIGHT=어머니\nR2_LEFT=지민\nR2_RIGHT=이봄 (아기)"
        val plan = PlanParse.parse(text, riders)
        assertEquals(mapOf("R1_LEFT" to "운전자", "R1_RIGHT" to "어머니", "R2_LEFT" to "지민", "R2_RIGHT" to "이봄"), plan)
        assertTrue(PlanRules.check(plan, riders, seats).none { it.level == IssueLevel.HARD })
    }

    @Test
    fun babyInFrontIsRejected() {
        val plan = mapOf("R1_LEFT" to "운전자", "R1_RIGHT" to "이봄", "R2_LEFT" to "지민", "R2_RIGHT" to "어머니")
        val issues = PlanRules.check(plan, riders, seats)
        assertTrue(issues.any { it.level == IssueLevel.HARD && it.text.contains("이봄") })
        assertFalse(CheckedPlan(plan, issues, "AI").passed)
    }

    @Test
    fun missingAndDoubleAreRejected() {
        val plan = mapOf("R1_LEFT" to "운전자", "R2_LEFT" to "지민", "R2_RIGHT" to "지민", "R3_LEFT" to "누구")
        val hard = PlanRules.check(plan, riders, seats).filter { it.level == IssueLevel.HARD }.map { it.text }
        assertTrue(hard.any { it.contains("두 자리") })
        assertTrue(hard.any { it.contains("이봄 자리가 없어요") })
        assertTrue(hard.any { it.contains("3열") || it.contains("R3_LEFT") })
    }

    @Test
    fun fallbackAlwaysPasses() {
        val plan = PlanRules.fallback(riders, seats)
        assertTrue(PlanRules.check(plan, riders, seats).none { it.level == IssueLevel.HARD })
        assertEquals("이봄", plan["R2_RIGHT"])
    }

    @Test
    fun babyWithoutCarerIsWarning() {
        val r = ridersOf(TripDraft(infants = 1, adults = 1), emptyList(), emptyList())
        val plan = mapOf("R1_LEFT" to "운전자", "R1_RIGHT" to "어른", "R2_RIGHT" to "아기")
        val issues = PlanRules.check(plan, r, seats)
        assertTrue(issues.any { it.level == IssueLevel.SOFT && it.text.contains("보호자") })
        assertTrue(issues.none { it.level == IssueLevel.HARD })
    }

    @Test
    fun promptHasRulesAndPeople() {
        assertTrue(PlanPrompt.SYSTEM.contains("에어백"))
        val p = PlanPrompt.build(riders, seats)
        assertTrue(p.contains("이봄 (아기, 뒤보기 카시트)"))
        assertTrue(p.contains("지민 (아이, 부스터 시트, 기타: 차멀미)"))
    }

    @Test
    fun parseKoreanSeatNames() {
        val plan = PlanParse.parse("조수석: 어머니\n2열 왼쪽 - 지민\nR2_RIGHT 이봄", riders)
        assertEquals("어머니", plan["R1_RIGHT"])
        assertEquals("지민", plan["R2_LEFT"])
        assertEquals("이봄", plan["R2_RIGHT"])
    }

    @Test
    fun completeKeepsAiSeatsAndFillsMissing() {
        // 실제 폰 답: 어르신을 빼먹음
        val ai = PlanParse.parse("R1_LEFT=운전자\nR2_LEFT=이봄\nR2_RIGHT=지민", riders)
        assertFalse(CheckedPlan(ai, PlanRules.check(ai, riders, seats), "AI").passed)
        val (fixed, fixes) = PlanRules.complete(ai, riders, seats)
        assertEquals("이봄", fixed["R2_LEFT"])
        assertEquals("지민", fixed["R2_RIGHT"])
        assertEquals("어머니", fixed["R1_RIGHT"])
        assertEquals(1, fixes.size)
        assertTrue(PlanRules.check(fixed, riders, seats).none { it.level == IssueLevel.HARD })
    }

    @Test
    fun nameFirstAnswerAndCopiedExampleNamesAreDropped() {
        // 실제 폰 답 같은 실수: 운전자 두 번 · 예시 이름(민준) · 이름=좌석 형식
        val plan = PlanParse.parse("어머니=R1_RIGHT\nR2_LEFT=운전자\n민준=R2_RIGHT\n이봄=R2_LEFT\n지민=R2_RIGHT", riders)
        assertEquals(mapOf("R1_LEFT" to "운전자", "R1_RIGHT" to "어머니", "R2_LEFT" to "이봄", "R2_RIGHT" to "지민"), plan)
        assertTrue(PlanRules.check(plan, riders, seats).none { it.level == IssueLevel.HARD })
    }

    @Test
    fun promptAsksOnlyPassengers() {
        val p = PlanPrompt.build(riders, seats)
        assertTrue(p, p.contains("빈 좌석: R1_RIGHT, R2_LEFT, R2_RIGHT"))
        assertTrue(p, p.endsWith("답할 사람: 어머니, 지민, 이봄"))
        assertFalse(p, p.contains("운전자 ("))
        assertFalse(PlanPrompt.SYSTEM.contains("민준"))
    }

    @Test
    fun completeMovesBabyOutOfFront() {
        val ai = mapOf("R1_LEFT" to "운전자", "R1_RIGHT" to "이봄")
        val (fixed, _) = PlanRules.complete(ai, riders, seats)
        assertTrue(fixed.filterValues { it == "이봄" }.keys.single().startsWith("R2"))
        assertTrue(PlanRules.check(fixed, riders, seats).none { it.level == IssueLevel.HARD })
    }
}
