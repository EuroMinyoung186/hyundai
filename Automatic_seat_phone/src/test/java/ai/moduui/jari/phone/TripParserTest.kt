package ai.moduui.jari.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripParserTest {
    private fun p(s: String) = TripParser.parse(s)!!.draft

    @Test
    fun designSentence() {
        val d = p("어머니랑 애 둘, 유모차랑 캐리어 하나")
        assertEquals(1, d.seniors)
        assertEquals(2, d.children)
        assertEquals(0, d.infants)
        assertEquals(1, d.strollers)
        assertEquals(1, d.suitcases)
    }

    @Test
    fun babyAndDestination() {
        val d = p("강릉 가는데 할머니랑 아기 하나, 유모차랑 캐리어 2개")
        assertEquals("강릉", d.destination)
        assertEquals(1, d.seniors)
        assertEquals(1, d.infants)
        assertEquals(1, d.strollers)
        assertEquals(2, d.suitcases)
    }

    @Test
    fun grandmaHouseIsDestinationNotPassenger() {
        val d = p("할머니댁 가요. 아내랑 아기")
        assertEquals("할머니댁", d.destination)
        assertEquals(0, d.seniors)
        assertEquals(1, d.adults)
        assertEquals(1, d.infants)
    }

    @Test
    fun babyBagIsNotBaby() {
        val d = p("아기 한 명, 아기 짐가방 두 개")
        assertEquals(1, d.infants)
        assertEquals(2, d.babyBags)
    }

    @Test
    fun numberBeforeWord() {
        assertEquals(2, p("두 아이랑 같이 가요").children)
        assertEquals(3, p("세 명의 아이").children)
    }

    @Test
    fun ordinalIsNotCount() {
        val d = p("첫째 둘째 아기 하나")
        assertEquals(2, d.children)
        assertEquals(1, d.infants)
    }

    @Test
    fun pluralDefaults() {
        assertEquals(2, p("부모님 모시고 가요").seniors)
        assertEquals(2, p("애들 태우고 가요").children)
        assertEquals(2, p("쌍둥이").infants)
    }

    @Test
    fun numberNotStolenByNeighbour() {
        // 유모차 뒤의 "캐리어 하나" 는 캐리어 수. 유모차는 수가 없으니 1.
        val d = p("유모차랑 캐리어 셋")
        assertEquals(1, d.strollers)
        assertEquals(3, d.suitcases)
    }

    @Test
    fun otherPlaceFromSentence() {
        assertEquals("양평", p("양평으로 가요 아기 하나").destination)
    }

    @Test
    fun heardSlots() {
        val r = TripParser.parse("어머니랑 유모차")!!
        assertTrue(Slot.SENIOR in r.heard)
        assertTrue(Slot.STROLLER in r.heard)
        assertTrue(Slot.CHILD !in r.heard)
    }

    @Test
    fun nothingUnderstood() {
        assertNull(TripParser.parse("오늘 날씨 좋다"))
        assertNull(TripParser.parse("   "))
    }

    // ---- apply: 지금 여정에 한 문장씩 반영 ----

    private fun a(s: String, base: TripDraft) = TripParser.apply(s, base)!!

    @Test
    fun addOneChild() {
        val (d, ch) = a("아이 한 명 추가", TripDraft(children = 1))
        assertEquals(2, d.children)
        assertEquals(listOf("아이 1명 추가"), ch.map { it.text })
    }

    @Test
    fun addWithDeo() {
        val (d, ch) = a("어른 둘 더", TripDraft())
        assertEquals(2, d.adults)
        assertEquals("어른 2명 추가", ch.single().text)
    }

    @Test
    fun removeSuitcase() {
        val (d, ch) = a("캐리어 빼 줘", TripDraft(suitcases = 2))
        assertEquals(1, d.suitcases)
        assertEquals("캐리어 1개 빠짐", ch.single().text)
    }

    @Test
    fun setKeepsUnmentioned() {
        val (d, ch) = a("어머니랑 애 둘", TripDraft(infants = 1, strollers = 1))
        assertEquals(1, d.seniors)
        assertEquals(2, d.children)
        assertEquals(1, d.infants) // 말하지 않은 칸은 그대로
        assertEquals(1, d.strollers)
        assertEquals(2, ch.size)
    }

    @Test
    fun destinationNotTakenFromSpeech() {
        val (d, _) = a("강릉 가는데 아기 하나", TripDraft(infants = 0))
        assertEquals("", d.destination)
        assertEquals(1, d.infants)
    }
}
