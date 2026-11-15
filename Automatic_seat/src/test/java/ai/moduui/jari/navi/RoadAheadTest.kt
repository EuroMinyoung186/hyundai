package ai.moduui.jari.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadAheadTest {
    private fun state(cautions: List<NaviCaution> = emptyList(), turn: NaviTurn? = null) =
        NaviState(guiding = true, cautions = cautions, nextTurn = turn)

    @Test
    fun speedBumpWhileAsleepIsUrgentAndUsesNickname() {
        val a = RoadAhead.advice(state(listOf(NaviCaution("SPEED_BUMP", 300))), asleep = true, nickname = "지민")
        assertTrue(a!!.urgent)
        assertTrue(a.text.contains("300m 앞 과속방지턱"))
        assertTrue(a.text.contains("지민이가 자요"))
        assertEquals("SPEED_BUMP", a.key)
    }

    @Test
    fun speedBumpWhileAwakeIsGentleOnly() {
        val a = RoadAhead.advice(state(listOf(NaviCaution("SPEED_BUMP", 300))), asleep = false, nickname = "지민")
        assertFalse(a!!.urgent)
    }

    @Test
    fun farOrEnforcementCautionsAreIgnored() {
        assertNull(RoadAhead.advice(state(listOf(NaviCaution("SPEED_BUMP", 900))), asleep = true, nickname = ""))
        assertNull(RoadAhead.advice(state(listOf(NaviCaution("BUS_LANE_VIOLATION_ENFORCEMENT", 100))), asleep = true, nickname = ""))
    }

    @Test
    fun sharpTurnOnlyMattersWhenAsleep() {
        val s = state(turn = NaviTurn("TURN_UTURN", 200, ""))
        assertTrue(RoadAhead.advice(s, asleep = true, nickname = "")!!.urgent)
        assertNull(RoadAhead.advice(s, asleep = false, nickname = ""))
        // 보통 좌회전은 알리지 않음
        assertNull(RoadAhead.advice(state(turn = NaviTurn("TURN_LEFT", 100, "")), asleep = true, nickname = ""))
    }

    @Test
    fun koreanParticles() {
        assertEquals("아기가", RoadAhead.subjectKo("아기"))
        assertEquals("지민이가", RoadAhead.subjectKo("지민"))
        assertEquals("유턴이에요", RoadAhead.copulaKo("유턴"))
        assertEquals("회전교차로예요", RoadAhead.copulaKo("회전교차로"))
    }

    @Test
    fun legsSplitIntoWaypointAndDestination() {
        val s = NaviState(legs = listOf(NaviLeg("휴게소", 12000, 540, true), NaviLeg("강릉", 190000, 7600, false)))
        assertEquals("휴게소", RoadAhead.nextWaypoint(s)!!.name)
        assertEquals("강릉", RoadAhead.destinationLeg(s)!!.name)
        assertEquals("12.0km", RoadAhead.distanceKo(12000))
        assertEquals("2시간 7분", RoadAhead.durationKo(7600))
    }
}
