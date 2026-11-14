package ai.moduui.jari.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ChipsTest {
    private val bom = PresetParser.personFrom("이봄", "10개월", "", "", "", 1)!!
    private val jimin = PresetParser.personFrom("지민", "5", "110", "", "", 2)!!
    private val bag = PresetParser.luggageFrom("큰 캐리어", "28인치", "", "", 3)!!

    @Test
    fun namedAndCounted() {
        val d = TripDraft(infants = 1, children = 2, suitcases = 1)
        val chips = draftChips(d, listOf(1L, 2L, 3L), listOf(bom, jimin), listOf(bag)).map { it.label }
        assertEquals(listOf("지민 (아이)", "아이 1 더", "이봄 (아기)", "큰 캐리어"), chips)
    }

    @Test
    fun noNamesShowsCounts() {
        val chips = draftChips(TripDraft(children = 2), emptyList(), listOf(jimin), emptyList()).map { it.label }
        assertEquals(listOf("아이 2"), chips)
    }
}
