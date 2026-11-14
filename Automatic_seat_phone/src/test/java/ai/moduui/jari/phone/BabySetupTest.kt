package ai.moduui.jari.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BabySetupTest {
    @Test
    fun feedTextReadsNaturally() {
        assertEquals("3시간", feedText(180))
        assertEquals("2시간 30분", feedText(150))
        assertEquals("45분", feedText(45))
    }

    @Test
    fun babyDetailShowsSeatAndFeeding() {
        val b = PresetParser.personFrom("이봄", "10개월", "72", "9", "", 1)!!
            .copy(hasCarSeat = true, carSeatDir = CarSeatDir.REAR, carSeatAngle = 40, feedMin = 180)
        assertEquals(Who.BABY, b.who)
        assertTrue(b.detail, b.detail.contains("후방 카시트 40°"))
        assertTrue(b.detail, b.detail.contains("수유 3시간마다"))
    }
}
