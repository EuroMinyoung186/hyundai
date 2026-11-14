package ai.moduui.jari.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSeatMemoryTest {
    private val bom = PresetParser.personFrom("이봄", "10개월", "", "", "", 1)!!

    @Test
    fun chosenCarSeatOverridesSuggestion() {
        val m = bom.copy(hasCarSeat = true, carSeatDir = CarSeatDir.REAR, carSeatAngle = 40)
        assertEquals(SeatNeed.REAR_SEAT, m.need)
        assertEquals("후방 카시트 40°", m.seatText)
        assertTrue(m.carSeatWarnings.isEmpty())
    }

    @Test
    fun frontFacingTooEarlyWarns() {
        val m = bom.copy(hasCarSeat = true, carSeatDir = CarSeatDir.FRONT)
        assertEquals(SeatNeed.CAR_SEAT, m.need)
        assertTrue(m.carSeatWarnings.any { it.contains("후방") })
    }

    @Test
    fun noCarSeatWarnsLegal() {
        val m = bom.copy(hasCarSeat = false)
        assertEquals("카시트 없음", m.seatText)
        assertTrue(m.carSeatWarnings.any { it.contains("법") })
    }

    @Test
    fun habitsGroupBySeatAndPerson() {
        val r = listOf(
            SeatRecord(1, "R2_RIGHT", "이봄", "후방 카시트 40°", 0, 100, "출발 준비"),
            SeatRecord(2, "R2_RIGHT", "이봄", "후방 카시트 40°", 10, 90, "직접 조절"),
            SeatRecord(3, "R1_RIGHT", "어머니", "어르신", 90, 70, "출발 준비"),
        )
        val h = seatHabits(r)
        assertEquals(listOf("R1_RIGHT", "R2_RIGHT"), h.map { it.first })
        val bomHabit = h.first { it.first == "R2_RIGHT" }.second.single()
        assertEquals(2, bomHabit.times)
        assertEquals(10, bomHabit.last.foreAft)
        assertEquals(10, lastAt(r, "R2_RIGHT", "이봄")!!.foreAft)
    }

    @Test
    fun backrestAngleRoundTrip() {
        assertEquals(15, backrestDeg(100))
        assertEquals(50, backrestDeg(0))
        assertTrue(kotlin.math.abs(75 - degToBackrest(backrestDeg(75))) <= 2)
    }
}
