package ai.moduui.core.route

import ai.moduui.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePathTest {
    // 북쪽으로 약 1.11km 씩 두 구간 (위도 0.01도)
    private val a = GeoPoint(37.40, 127.00)
    private val b = GeoPoint(37.41, 127.00)
    private val c = GeoPoint(37.42, 127.00)
    private val path = RoutePath(listOf(a, b, c))

    @Test fun lengthIsSumOfSegments() {
        assertEquals(2.224, path.lengthKm, 0.01)
    }

    @Test fun pointAtWalksAlongRoad() {
        assertEquals(a, path.pointAt(-1.0))
        assertEquals(c, path.pointAt(99.0))
        val mid = path.pointAt(path.lengthKm / 2)
        assertEquals(37.41, mid.lat, 1e-4)
        val quarter = path.pointAt(path.lengthKm / 4)
        assertEquals(37.405, quarter.lat, 1e-4)
    }

    @Test fun upToReturnsDrivenPart() {
        assertTrue(path.upTo(0.0).isEmpty())
        val part = path.upTo(path.lengthKm * 0.75)
        assertEquals(3, part.size)
        assertEquals(37.415, part.last().lat, 1e-4)
    }

    @Test fun locateFindsDistanceAlongRoad() {
        // 두 번째 구간 가운데 (37.415) 바로 옆 (경도 0.001 ≈ 88m)
        val (along, off) = path.locate(GeoPoint(37.415, 127.001))
        assertEquals(path.lengthKm * 0.75, along, 0.01)
        assertEquals(0.088, off, 0.01)
        assertEquals(0.0, path.locate(GeoPoint(37.39, 127.0)).first, 1e-6)
    }

    @Test fun bearingPointsAlongRoad() {
        // 북쪽으로만 가는 길 → 0도 (북)
        assertEquals(0.0, path.bearingAt(1.0).toDouble(), 1.0)
        val east = RoutePath(listOf(GeoPoint(37.0, 127.0), GeoPoint(37.0, 127.02)))
        assertEquals(90.0, east.bearingAt(0.5).toDouble(), 1.0)
    }

    @Test fun matchesOnlyNearSameEnds() {
        assertTrue(path.matches(GeoPoint(37.401, 127.001), GeoPoint(37.421, 127.0)))
        assertTrue(path.matches(null, c))
        assertFalse(path.matches(GeoPoint(37.0, 127.0), c))
        assertFalse(path.matches(a, GeoPoint(37.6, 127.0)))
        assertFalse(path.matches(a, null))
    }
}
