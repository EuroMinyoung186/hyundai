package ai.moduui.core.route

import ai.moduui.core.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 도로를 따라가는 경로 (좌표 목록). 앞에서부터 간 거리(km)로 지도 위 위치를 찾음.
 * NaviHelper 가 경로 모양을 주지 않아서, 시연용 실제 도로 경로를 지도에 그리고 차를 움직일 때 씀.
 */
class RoutePath(val points: List<GeoPoint>) {
    /** points[i] 까지 누적 거리 (km) */
    private val cum: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + km(points[i - 1], points[i])
    }

    val lengthKm: Double get() = if (cum.isEmpty()) 0.0 else cum.last()

    /** 앞에서 [distKm] 간 곳. 범위 밖이면 처음 · 끝 */
    fun pointAt(distKm: Double): GeoPoint {
        require(points.isNotEmpty())
        if (distKm <= 0.0) return points.first()
        if (distKm >= lengthKm) return points.last()
        var lo = 0
        var hi = cum.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= distKm) lo = mid else hi = mid
        }
        val seg = cum[hi] - cum[lo]
        val f = if (seg <= 0.0) 0.0 else (distKm - cum[lo]) / seg
        val a = points[lo]
        val b = points[hi]
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
    }

    /** 앞에서 [distKm] 간 곳의 진행 방향 (북쪽 0도, 시계 방향). 내비 화살표를 돌릴 때 씀 */
    fun bearingAt(distKm: Double): Float {
        val a = pointAt((distKm - 0.1).coerceAtLeast(0.0))
        val b = pointAt((distKm + 0.1).coerceAtMost(lengthKm))
        val y = sin(Math.toRadians(b.lng - a.lng)) * cos(Math.toRadians(b.lat))
        val x = cos(Math.toRadians(a.lat)) * sin(Math.toRadians(b.lat)) - sin(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * cos(Math.toRadians(b.lng - a.lng))
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    /** 앞에서 [distKm] 간 곳까지의 경로 (지나온 부분) */
    fun upTo(distKm: Double): List<GeoPoint> {
        if (distKm <= 0.0) return emptyList()
        val idx = cum.indexOfFirst { it > distKm }.let { if (it < 0) points.size else it }
        return points.subList(0, idx) + pointAt(distKm)
    }

    /** 끝이 [to] 에서 [radiusKm] 안이고, 출발점을 알면 처음도 [radiusKm] 안이면 같은 경로로 봄 */
    /**
     * [p] 에 가장 가까운 경로 위 지점까지 앞에서 간 거리(km)와 그 지점까지의 거리(km).
     * 경유지(휴게소)에서 차를 세울 곳을 찾을 때 씀.
     */
    fun locate(p: GeoPoint): Pair<Double, Double> {
        var bestAlong = 0.0
        var bestOff = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            // 짧은 구간이라 위경도를 평면으로 보고 투영 (경도는 위도에 맞춰 줄임)
            val k = cos(Math.toRadians(a.lat))
            val dx = (b.lng - a.lng) * k
            val dy = b.lat - a.lat
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((p.lng - a.lng) * k * dx + (p.lat - a.lat) * dy) / len2).coerceIn(0.0, 1.0)
            val q = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            val off = km(p, q)
            if (off < bestOff) {
                bestOff = off
                bestAlong = cum[i] + (cum[i + 1] - cum[i]) * t
            }
        }
        return bestAlong to bestOff
    }

    fun matches(from: GeoPoint?, to: GeoPoint?, radiusKm: Double = 3.0): Boolean =
        points.size >= 2 && to != null && km(points.last(), to) <= radiusKm &&
            (from == null || km(points.first(), from) <= radiusKm)

    companion object {
        /** 두 지점 사이 거리 (km, 하버사인) */
        fun km(a: GeoPoint, b: GeoPoint): Double {
            val r = 6371.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
            return 2 * r * atan2(sqrt(h), sqrt(1 - h))
        }
    }
}
