package ai.moduui.jari.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatLayoutTest {
    private val bom = Member(1, "이봄", Who.BABY, ageMonths = 10)
    private val mom = Member(2, "엄마", Who.ADULT)
    private val grandma = Member(3, "할머니", Who.SENIOR)

    @Test
    fun draftCountsFromLayout() {
        val l = SeatLayout()
            .put("R2_RIGHT", Placed.of(bom))
            .put("R2_LEFT", Placed.of(mom))
            .put("R1_RIGHT", Placed(GenericRider.SENIOR.label, Who.SENIOR, null))
            .addTrunk(TrunkPut(10, "큰 캐리어", Bag.SUITCASE, 90))
            .addTrunk(TrunkPut(11, "유모차", Bag.STROLLER, 125))
        val d = l.draft
        assertEquals(1, d.infants)
        assertEquals(1, d.adults)
        assertEquals(1, d.seniors)
        assertEquals(1, d.suitcases)
        assertEquals(1, d.strollers)
        assertEquals(215, l.luggageLiters)
        assertEquals(listOf(1L, 2L), l.picked.sorted())
        assertEquals("운전자", l.plan["R1_LEFT"])
    }

    @Test
    fun driverSeatIsNotCountedAsPassenger() {
        val l = SeatLayout().put(SeatLayout.DRIVER, Placed.of(mom))
        assertEquals(0, l.draft.adults)
        assertEquals("엄마", l.plan[SeatLayout.DRIVER])
        assertTrue(l.riders.first().driver)
    }

    @Test
    fun sameMemberMovesSeat() {
        val l = SeatLayout().put("R2_LEFT", Placed.of(bom)).put("R2_RIGHT", Placed.of(bom))
        assertEquals(setOf("R2_RIGHT"), l.seats.keys)
    }

    @Test
    fun genericNamesAreUnique() {
        val child = Placed(GenericRider.CHILD.label, Who.CHILD, SeatNeed.BOOSTER)
        val l = SeatLayout().put("R2_LEFT", child).put("R2_RIGHT", child)
        assertEquals(setOf("아이", "아이2"), l.seats.values.map { it.label }.toSet())
    }

    @Test
    fun childSeatBlockedInFrontRow() {
        assertNotNull(LayoutRules.blocked("R1_RIGHT", Placed.of(bom)))
        assertNotNull(LayoutRules.blocked(SeatLayout.DRIVER, Placed.of(bom)))
        assertNull(LayoutRules.blocked("R2_RIGHT", Placed.of(bom)))
        assertNull(LayoutRules.blocked("R1_RIGHT", Placed.of(grandma)))
    }

    @Test
    fun warnsBabyWithoutCaregiver() {
        val alone = SeatLayout().put("R2_RIGHT", Placed.of(bom))
        assertTrue(LayoutRules.warnings(alone, 600).any { "보호자" in it })
        val withMom = alone.put("R2_LEFT", Placed.of(mom))
        assertTrue(LayoutRules.warnings(withMom, 600).none { "보호자" in it })
    }

    @Test
    fun parsePostureAnswer() {
        val p = PosturePrompt.parse("R1_RIGHT=22도 앞뒤40\nR2_RIGHT: 20° \n설명")
        assertEquals(22 to 40, p["R1_RIGHT"])
        assertEquals(20 to null, p["R2_RIGHT"])
    }

    @Test
    fun rulesForceChildSeatUprightAndClampRecline() {
        val l = SeatLayout().put("R2_RIGHT", Placed.of(bom)).put("R2_LEFT", Placed.of(mom))
        val r = PostureRules.check(mapOf("R2_RIGHT" to (40 to 50), "R2_LEFT" to (48 to 30)), l.plan, l.riders, emptyList())
        assertEquals(SeatGuessRules.CHILD_SEAT_DEG, backrestDeg(r.guess.getValue("R2_RIGHT").backrest))
        assertEquals(PostureRules.MAX_RIDE_DEG, backrestDeg(r.guess.getValue("R2_LEFT").backrest))
        assertEquals(30, r.guess.getValue("R2_LEFT").foreAft)
        assertEquals(2, r.fixes.size)
        assertEquals(2, r.aiSeats)
    }

    @Test
    fun rearFacingPullsFrontSeatForward() {
        // 10개월 → 뒤보기 카시트
        val l = SeatLayout().put("R2_RIGHT", Placed.of(bom)).put("R1_RIGHT", Placed.of(grandma))
        val r = PostureRules.check(mapOf("R1_RIGHT" to (22 to 30)), l.plan, l.riders, emptyList())
        assertEquals(SeatGuessRules.ROOM_FOR_REAR_SEAT, r.guess.getValue("R1_RIGHT").foreAft)
    }

    private fun ride(seats: Map<String, String>, pos: Map<String, Pair<Int?, Int?>>, trip: TripDraft = TripDraft()) =
        RideRecord(at = 1L, destination = "시댁", km = 120.0, minutes = 90, trip = trip, luggageLiters = 0, seats = seats, positions = pos)

    @Test
    fun pastPosePrefersSamePersonSameSeat() {
        val r = ride(mapOf("R1_LEFT" to "운전자", "R2_LEFT" to "엄마", "R2_RIGHT" to "아이"), mapOf("R2_LEFT" to (40 to 70), "R2_RIGHT" to (50 to 90)))
        assertEquals(40 to 70, PastPose.of(r, "엄마", "R1_RIGHT"))
        // 이름 없는 아이는 같은 자리의 같은 종류로
        assertEquals(50 to 90, PastPose.of(r, "아이2", "R2_RIGHT"))
        assertNull(PastPose.of(r, "할머니", "R1_RIGHT"))
    }

    @Test
    fun similarRideUsedWhenNoAi() {
        val l = SeatLayout().put("R2_LEFT", Placed.of(mom))
        val sim = SimilarRide(ride(mapOf("R2_LEFT" to "엄마"), mapOf("R2_LEFT" to (30 to degToBackrest(28)))), 90, listOf("같은 사람 1/1"))
        val r = PostureRules.check(emptyMap(), l.plan, l.riders, emptyList(), sim)
        val g = r.guess.getValue("R2_LEFT")
        assertEquals(28, backrestDeg(g.backrest))
        assertEquals(30, g.foreAft)
        assertTrue(g.why.startsWith("비슷한 여정 90%"))
    }

    @Test
    fun similarRideStillObeysChildSeatRule() {
        val l = SeatLayout().put("R2_RIGHT", Placed.of(bom))
        val sim = SimilarRide(ride(mapOf("R2_RIGHT" to "이봄"), mapOf("R2_RIGHT" to (50 to degToBackrest(40)))), 90, emptyList())
        val r = PostureRules.check(emptyMap(), l.plan, l.riders, emptyList(), sim)
        assertEquals(SeatGuessRules.CHILD_SEAT_DEG, backrestDeg(r.guess.getValue("R2_RIGHT").backrest))
    }

    @Test
    fun promptCarriesRetrievedPose() {
        val l = SeatLayout().put("R2_LEFT", Placed.of(mom))
        val sim = SimilarRide(ride(mapOf("R2_LEFT" to "엄마"), mapOf("R2_LEFT" to (30 to degToBackrest(26)))), 87, emptyList())
        assertTrue("[비슷한 여정 87%: 26도 앞뒤30]" in PosturePrompt.build(l.plan, l.riders, emptyList(), sim))
    }

    @Test
    fun sitLikeBeforeRebuildsLayout() {
        val p = PastTrip(
            1L, "11월 12일 시댁",
            mapOf("R1_LEFT" to "운전자", "R1_RIGHT" to "할머니", "R2_RIGHT" to "이봄", "R2_LEFT" to "아이"),
            TripDraft(strollers = 1, suitcases = 2),
        )
        val l = LayoutFromPast.build(p, listOf(bom, grandma))
        // 이봄 · 할머니는 구성원으로, "아이" 는 종류로
        assertEquals(1L, l.seats.getValue("R2_RIGHT").memberId)
        assertEquals(3L, l.seats.getValue("R1_RIGHT").memberId)
        assertEquals(GenericRider.CHILD.need, l.seats.getValue("R2_LEFT").need)
        assertEquals(3, l.trunk.size)
        assertEquals(1, l.draft.strollers)
        assertEquals(2, l.draft.suitcases)
    }

    @Test
    fun sitLikeBeforeSkipsUnsafeSeat() {
        // 예전에 아기가 조수석이었던 기록이 있어도 안전 규칙에 걸리면 넣지 않음
        val p = PastTrip(1L, "옛 기록", mapOf("R1_RIGHT" to "이봄"), TripDraft())
        assertTrue(LayoutFromPast.build(p, listOf(bom)).seats.isEmpty())
    }

    @Test
    fun driverPreviewIsDrivableAndFlagged() {
        val l = SeatLayout().put("R2_LEFT", Placed.of(mom))
        // 에뮬레이터 기본값: 앞뒤 0 · 등받이 0 (50°, 완전히 눕힘)
        val r = PostureRules.check(emptyMap(), l.plan, l.riders, emptyList(), driverNow = 0 to 0)
        val d = r.guess.getValue(SeatLayout.DRIVER)
        assertEquals(PostureRules.DRIVE_MAX_DEG, backrestDeg(d.backrest))
        assertEquals(PostureRules.DRIVE_MIN_FORE, d.foreAft)
        assertTrue(d.why.startsWith("운전할 수 없는"))
        assertTrue(r.fixes.any { "운전석" in it })
        // 운전할 수 있는 자세면 그대로
        val ok = PostureRules.check(emptyMap(), l.plan, l.riders, emptyList(), driverNow = 50 to degToBackrest(24))
        assertEquals(24, backrestDeg(ok.guess.getValue(SeatLayout.DRIVER).backrest))
        assertTrue(ok.fixes.none { "운전석" in it })
    }

    @Test
    fun frontPassengerLimits() {
        val l = SeatLayout().put("R1_RIGHT", Placed.of(mom))
        val r = PostureRules.check(mapOf("R1_RIGHT" to (34 to 95)), l.plan, l.riders, emptyList())
        val g = r.guess.getValue("R1_RIGHT")
        assertEquals(PostureRules.MAX_FRONT_DEG, backrestDeg(g.backrest))
        assertEquals(PostureRules.FRONT_MAX_FORE, g.foreAft)
    }

    @Test
    fun promptStatesSeatLimits() {
        assertTrue("운전석(R1_LEFT)은 답하지 않는다" in PosturePrompt.SYSTEM)
        assertTrue("조수석(R1_RIGHT)은 15~30도" in PosturePrompt.SYSTEM)
    }

    @Test
    fun noAiFallsBackToRules() {
        val l = SeatLayout().put("R2_LEFT", Placed.of(mom))
        val r = PostureRules.check(emptyMap(), l.plan, l.riders, emptyList())
        assertEquals(0, r.aiSeats)
        assertEquals(SeatGuessRules.NORMAL_DEG, backrestDeg(r.guess.getValue("R2_LEFT").backrest))
    }
}
