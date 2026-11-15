package ai.moduui.jari.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 트렁크 짐: 크기 · 무게 칩과 못 읽은 크기 안내 */
class TrunkRoughTest {
    @Test fun sizeChipScalesTypeDefault() {
        assertEquals(125, TrunkPut.typed("유모차", 1)!!.liters)
        assertEquals(188, TrunkPut.typed("유모차", 1, RoughSize.LARGE)!!.liters)
        assertEquals(63, TrunkPut.typed("유모차", 1, RoughSize.SMALL)!!.liters)
    }

    @Test fun writtenSizeBeatsChip() {
        assertTrue(TrunkPut.wroteSize("24인치 캐리어"))
        assertEquals(60, TrunkPut.typed("24인치 캐리어", 1, RoughSize.XLARGE)!!.liters)
        assertEquals(100, TrunkPut.typed("유모차 100L", 1, RoughSize.SMALL)!!.liters)
    }

    @Test fun weightChipEstimatesSuitcaseOnly() {
        // 캐리어는 무게로 부피 어림 (22kg × 4 = 88L), 유모차는 무게로 부피를 바꾸지 않음
        assertEquals(88, TrunkPut.typed("캐리어", 1, weight = RoughWeight.HEAVY)!!.liters)
        assertEquals(125, TrunkPut.typed("유모차", 1, weight = RoughWeight.HEAVY)!!.liters)
        assertEquals("무거움", TrunkPut.typed("유모차", 1, weight = RoughWeight.HEAVY)!!.weight)
        // 크기 칩이 무게보다 우선
        assertEquals(45, TrunkPut.typed("캐리어", 1, RoughSize.SMALL, RoughWeight.HEAVY)!!.liters)
    }

    @Test fun writtenWeightShownAsKg() {
        assertEquals("18kg", TrunkPut.typed("캐리어 18kg", 1, weight = RoughWeight.LIGHT)!!.weight)
    }

    @Test fun unreadSizeIsReported() {
        assertEquals("1L", PresetParser.unreadSize("유모차 1L"))
        assertEquals("900L", PresetParser.unreadSize("유모차 900L"))
        assertNull(PresetParser.unreadSize("유모차 100L"))
        assertNull(PresetParser.unreadSize("디럭스 유모차"))
        assertFalse(TrunkPut.wroteSize("유모차 1L"))
    }

    @Test fun chipShowsSizeAndWeight() {
        val t = SeatLayout().addTrunk(TrunkPut.typed("유모차", 7, RoughSize.LARGE, RoughWeight.HEAVY)!!).trunk.single()
        assertEquals("유모차 · 188L · 무거움", t.chip)
        assertEquals("유모차 · 125L", TrunkPut.typed("유모차", 8)!!.chip)
        assertEquals(188, SeatLayout().addTrunk(t).luggageLiters)
    }
}
