package ai.moduui.jari.phone

import org.junit.Assert.assertEquals
import org.junit.Test

/** 말로 빼기: 하나만 있을 때도 빠져야 함 (예전엔 "그 수로 맞춤"으로 우연히 맞던 말들) */
class TripRemoveTest {
    private val one = TripDraft(seniors = 1, adults = 1, children = 1, infants = 1, strollers = 1, suitcases = 1, babyBags = 1)

    private fun removed(s: String, base: TripDraft = one) = TripParser.apply(s, base)!!.second.map { it.text }

    @Test
    fun removeVerbs() {
        val cases = mapOf(
            "캐리어 빼 줘" to "캐리어 1개 빠짐",
            "유모차 삭제" to "유모차 1개 빠짐",
            "캐리어 제거" to "캐리어 1개 빠짐",
            "아이 지워" to "아이 1명 빠짐",
            "아이 하나 줄여 줘" to "아이 1명 빠짐",
            "어른 1명 제외" to "어른 1명 빠짐",
            "유모차 필요 없어" to "유모차 1개 빠짐",
            "어머니 못 가" to "어르신 1명 빠짐",
            "어머니는 안 타" to "어르신 1명 빠짐",
            "유모차 안 실어" to "유모차 1개 빠짐",
            "아기 짐가방 빼" to "아기 짐가방 1개 빠짐",
        )
        for ((s, want) in cases) assertEquals(s, listOf(want), removed(s))
    }

    @Test
    fun eopsaeIsNotAChild() {
        assertEquals(listOf("캐리어 1개 빠짐"), removed("캐리어 없애 줘"))
    }

    @Test
    fun removeAll() {
        val base = TripDraft(suitcases = 3, adults = 3)
        assertEquals(listOf("캐리어 3개 빠짐"), removed("캐리어 다 빼", base))
        // 수를 말하면 그 수만큼만
        assertEquals(listOf("어른 2명 빠짐"), removed("어른 둘 다 빼", base))
    }
}
