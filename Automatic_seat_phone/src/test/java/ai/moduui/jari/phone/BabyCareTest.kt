package ai.moduui.jari.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BabyCareTest {
    @Test
    fun rulesSuggestNearbyForDiaper() {
        val s = CareRules.suggest("똥 쌌어요")
        assertEquals(listOf(CareStep(CareAction.NEARBY, "기저귀 갈 곳")), s)
    }

    @Test
    fun rulesSuggestTemperature() {
        assertEquals(CareAction.TEMP_DOWN, CareRules.suggest("더워해요").single().action)
        assertEquals(CareAction.TEMP_UP, CareRules.suggest("추워해요").single().action)
    }

    @Test
    fun rulesWokeUpBeatsSleep() {
        val s = CareRules.suggest("잠에서 깼어요").map { it.action }
        assertTrue(CareAction.NAVI_NORMAL in s)
        assertFalse(CareAction.NAVI_QUIET in s)
    }

    @Test
    fun rulesUnknownGivesNothing() {
        assertTrue(CareRules.suggest("안녕").isEmpty())
    }

    @Test
    fun parseKeepsKnownActionsOnly() {
        val s = CarePrompt.parse("NAVI_QUIET\nMUSIC: 자장가\nOPEN_WINDOW\n설명입니다")
        assertEquals(listOf(CareStep(CareAction.NAVI_QUIET), CareStep(CareAction.MUSIC, "자장가")), s)
    }

    @Test
    fun parseFillsDefaultValueAndDropsOpposite() {
        val s = CarePrompt.parse("- nearby\n- TEMP_UP\n- TEMP_DOWN\n- MUSIC_OFF\n- NAVI_QUIET")
        assertEquals(
            listOf(CareStep(CareAction.NEARBY, "쉬어 갈 곳"), CareStep(CareAction.TEMP_UP), CareStep(CareAction.MUSIC_OFF)),
            s,
        )
    }

    @Test
    fun parseIgnoresThinkBlock() {
        assertEquals(listOf(CareStep(CareAction.TEMP_DOWN)), CarePrompt.parse("<think>NAVI_QUIET</think>\nTEMP_DOWN"))
    }

    // org.json 은 단위 테스트에서 가짜(빈 구현)라 저장 · 메시지는 기기에서 확인

    @Test
    fun storeEmptyGivesDefaults() {
        assertEquals(CareDefaults.buttons, CareStore.decode(null))
    }

    @Test
    fun defaultsAreBasicThree() {
        assertEquals(listOf("ASLEEP", "AWAKE", "CRYING"), CareDefaults.buttons.map { it.state })
        assertTrue(CareDefaults.buttons.none { it.custom })
    }
}
