package ai.moduui.jari.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MembersTest {
    private fun person(s: String) = PresetParser.person(s, 1)!!
    private fun bag(s: String) = PresetParser.luggage(s, 1)!!

    private val family = listOf(person("지민 5살 110cm").copy(id = 1), person("서아 10개월").copy(id = 2), person("어머니").copy(id = 3))

    // ---- 사람 한 줄 ----

    @Test
    fun childWithAgeAndHeight() {
        val m = person("지민 5살 110cm")
        assertEquals("지민", m.name)
        assertEquals(Who.CHILD, m.who)
        assertEquals(60, m.ageMonths)
        assertEquals(110, m.heightCm)
        assertEquals(SeatNeed.BOOSTER, m.need)
        assertEquals(Slot.CHILD, m.kind)
        assertTrue(m.legalSeat)
    }

    @Test
    fun harnessUntil105() {
        assertEquals(SeatNeed.CAR_SEAT, person("하준 4살 102cm").need)
        assertEquals(SeatNeed.BOOSTER, person("하준 4살 106cm").need)
        assertEquals(SeatNeed.BELT, person("첫째 10살 146cm").need)
    }

    @Test
    fun babyByMonths() {
        val m = person("둘째 10개월")
        assertEquals("둘째", m.name)
        assertEquals(Who.BABY, m.who)
        assertEquals(SeatNeed.REAR_SEAT, m.need)
        assertEquals(Slot.INFANT, m.kind)
    }

    @Test
    fun toddlerByHeightNeedsCarSeat() {
        assertEquals(SeatNeed.CAR_SEAT, person("하준 키 95").need)
        assertEquals(SeatNeed.CAR_SEAT, person("하준 3살").need)
    }

    @Test
    fun tallChildUsesBelt() {
        val m = person("첫째 11살 152cm")
        assertEquals(SeatNeed.BELT, m.need)
        assertEquals(Slot.ADULT, m.kind)
    }

    @Test
    fun unknownSizeFallsBackToKind() {
        assertEquals(SeatNeed.BOOSTER, person("아들").need)
        assertEquals(SeatNeed.CAR_SEAT, person("아기").need)
        val mom = person("어머니")
        assertEquals(Who.SENIOR, mom.who)
        assertNull(mom.need)
        assertEquals(Slot.SENIOR, mom.kind)
        assertEquals(Slot.ADULT, person("민수").kind)
    }

    @Test
    fun koreanNumberAge() {
        assertEquals(60, person("지우 다섯 살").ageMonths)
    }

    // ---- 짐 한 줄 ----

    @Test
    fun suitcaseByInch() {
        val b = bag("24인치 캐리어 18kg")
        assertEquals(Bag.SUITCASE, b.bag)
        assertEquals(24, b.inches)
        assertEquals(18, b.weightKg)
        assertEquals(60, b.estLiters)
    }

    @Test
    fun carryOnByInch() {
        assertEquals(Bag.CARRY_ON, bag("20인치 캐리어").bag)
    }

    @Test
    fun strollerKinds() {
        assertEquals(Bag.STROLLER_DELUXE, bag("디럭스 유모차").bag)
        assertEquals(Bag.STROLLER, bag("절충형 유모차").bag)
        assertEquals(Bag.STROLLER_COMPACT, bag("휴대용 유모차").bag)
        assertEquals(Bag.STROLLER_TWIN, bag("쌍둥이 유모차").bag)
    }

    @Test
    fun sizeUnknownUsesDefault() {
        val b = bag("기저귀 가방")
        assertEquals(Bag.BABY_BAG, b.bag)
        assertEquals(Bag.BABY_BAG.defaultLiters, b.estLiters)
        assertTrue(b.detail.contains("기본값"))
    }

    @Test
    fun dimsAndLiters() {
        assertEquals(96, bag("아이스박스 60x40x40").estLiters)
        assertEquals(45, bag("골프백 45L").estLiters)
        assertEquals(Bag.COOLER, bag("아이스박스 60x40x40").bag)
    }

    @Test
    fun weightOnlySuitcase() {
        assertEquals(92, bag("캐리어 23kg").estLiters)
    }

    // ---- 여정 ----

    @Test
    fun nameCountsAsItsSeat() {
        val (d, ch) = TripParser.apply("지민이도 타", TripDraft(), family)!!
        assertEquals(1, d.children)
        assertEquals(listOf("아이 1명 추가"), ch.map { it.text })
    }

    @Test
    fun babyNameGoesToInfant() {
        val (d, _) = TripParser.apply("지민이랑 서아 같이 가", TripDraft(), family)!!
        assertEquals(1, d.children)
        assertEquals(1, d.infants)
    }

    @Test
    fun familyTripHasEveryoneAndLuggage() {
        val d = familyTrip(family, listOf(bag("디럭스 유모차"), bag("기저귀 가방")))
        assertEquals(1, d.children)
        assertEquals(1, d.infants)
        assertEquals(1, d.seniors)
        assertEquals(1, d.strollers)
        assertEquals(1, d.babyBags)
    }

    @Test
    fun familyWords() {
        for (s in listOf("우리 가족 다 타", "평소처럼", "가족 모두 가요")) assertTrue(s, FAMILY_WORDS.containsMatchIn(s))
    }

    // ---- 입력 칸 ----

    @Test
    fun personFields() {
        val m = PresetParser.personFrom("지민", "5살", "110", "", "차멀미", 1)!!
        assertEquals(Who.CHILD, m.who)
        assertEquals(SeatNeed.BOOSTER, m.need)
        assertEquals("차멀미", m.note)
        // 나이 칸에 숫자만 적으면 살
        assertEquals(36, PresetParser.personFrom("하준", "3", "", "", "", 1)!!.ageMonths)
        assertEquals(18, PresetParser.personFrom("둘째", "18개월", "", "", "", 1)!!.ageMonths)
    }

    @Test
    fun weightOnlyChild() {
        val m = PresetParser.personFrom("둘째", "", "", "15", "", 1)!!
        assertEquals(SeatNeed.CAR_SEAT, m.need)
        assertEquals(SeatNeed.REAR_SEAT, PresetParser.personFrom("막내", "", "", "9", "", 1)!!.need)
    }

    @Test
    fun onlyNameIsRequired() {
        assertNull(PresetParser.personFrom(" ", "5", "", "", "", 1))
        assertEquals(Who.SENIOR, PresetParser.personFrom("어머니", "", "", "", "허리 불편", 1)!!.who)
        assertEquals(Who.ADULT, PresetParser.personFrom("민수", "", "", "", "", 1)!!.who)
    }

    @Test
    fun luggageFields() {
        val a = PresetParser.luggageFrom("큰 캐리어", "28인치", "23", "", 1)!!
        assertEquals(Bag.SUITCASE, a.bag)
        assertEquals(90, a.estLiters)
        assertEquals(23, a.weightKg)
        // 크기에 숫자만: 34 이하는 인치, 넘으면 L
        assertEquals(Bag.CARRY_ON, PresetParser.luggageFrom("캐리어", "20", "", "", 1)!!.bag)
        assertEquals(60, PresetParser.luggageFrom("가방", "60", "", "", 1)!!.estLiters)
        assertEquals(240, PresetParser.luggageFrom("디럭스 유모차", "", "", "", 1)!!.estLiters)
        assertEquals(96, PresetParser.luggageFrom("아이스박스", "60x40x40", "", "", 1)!!.estLiters)
    }
}
