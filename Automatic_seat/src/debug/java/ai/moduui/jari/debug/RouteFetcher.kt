package ai.moduui.jari.debug

import ai.moduui.core.model.GeoPoint
import ai.moduui.core.route.RoutePath
import ai.moduui.jari.AppGraph
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 시연 전용 (debug): 내비 목적지가 바뀌면 그 목적지까지 실제 도로 경로를 받아 와 시연 지도 · 주행에 씀.
 * NaviHelper 는 경로 모양(좌표)을 주지 않고 에뮬레이터 내비 위치는 고정이라, 공개 OSRM(OpenStreetMap 도로)에 물어봄.
 * 보내는 값은 출발 · 목적지 좌표뿐 (개인정보 없음). 받은 경로는 메모리에만 둠. release 에는 없음.
 */
object RouteFetcher {
    /** 에뮬레이터 내비의 고정 출발점 (수정구 시흥동) */
    private val EMULATOR_START = GeoPoint(37.4127212, 127.094681)
    private const val TAG = "RouteFetcher"

    fun start(graph: AppGraph) {
        val c = graph.controller
        graph.scope.launch {
            c.ui.map { it.navi.route?.destination }.distinctUntilChanged().collect { dest ->
                if (dest == null) return@collect
                val from = c.ui.value.navi.current ?: EMULATOR_START
                // 경유지를 넣어 목적지가 경유지로 바뀐 경우 지금 도로 위면 그대로 (휴게소 목록도 그대로)
                c.currentRoadPath()?.takeIf { it.locate(dest).second <= 3.0 && graph.demoRoutes.match(from, dest) == null }?.let { return@collect }
                graph.restAreas.routeAreas = emptyList()
                // 이미 맞는 경로가 있으면 (강릉 assets · 받아 둔 것) 그대로, 없으면 받아 옴
                val path = graph.demoRoutes.match(from, dest) ?: withContext(Dispatchers.IO) { fetch(from, dest) }?.also {
                    graph.demoRoutes.add(it)
                    Log.i(TAG, "road path ${it.points.size} pts ${"%.1f".format(it.lengthKm)}km")
                    c.roadPathsChanged()
                } ?: return@collect
                // 그 길 위 우리 방향 휴게소를 찾아 휴게소 제안에 씀
                val areas = withContext(Dispatchers.IO) { RestAreaFinder.find(path) }
                if (c.ui.value.navi.route?.destination == dest && areas.isNotEmpty()) graph.restAreas.routeAreas = areas
            }
        }
    }

    private fun fetch(from: GeoPoint, to: GeoPoint): RoutePath? = runCatching {
        val url = URL("https://router.project-osrm.org/route/v1/driving/${from.lng},${from.lat};${to.lng},${to.lat}?overview=full&geometries=geojson")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "route-demo/1.0")
        val text = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }
        val coords = JSONObject(text).getJSONArray("routes").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        val pts = List(coords.length()) { i -> coords.getJSONArray(i).let { GeoPoint(it.getDouble(1), it.getDouble(0)) } }
        // 받은 경로의 끝을 내비 목적지에 맞춤 (도로 끝과 목적지 핀이 살짝 다를 수 있음)
        RoutePath(simplify(pts, 10.0) + to)
    }.onFailure { Log.w(TAG, "fetch failed", it) }.getOrNull()

    /** 더글러스-포이커로 점 줄이기 (도로 모양은 [epsM] 미터 안에서 유지) */
    private fun simplify(pts: List<GeoPoint>, epsM: Double): List<GeoPoint> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size).also { it[0] = true; it[pts.lastIndex] = true }
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to pts.lastIndex) }
        while (stack.isNotEmpty()) {
            val (i, j) = stack.removeLast()
            var maxD = 0.0
            var idx = -1
            for (k in i + 1 until j) {
                val d = perp(pts[k], pts[i], pts[j])
                if (d > maxD) { maxD = d; idx = k }
            }
            if (maxD > epsM && idx > 0) {
                keep[idx] = true
                stack.add(i to idx)
                stack.add(idx to j)
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    private fun perp(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val k = cos(Math.toRadians(a.lat)) * 111_000.0
        val x = (p.lng - a.lng) * k; val y = (p.lat - a.lat) * 111_000.0
        val dx = (b.lng - a.lng) * k; val dy = (b.lat - a.lat) * 111_000.0
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return sqrt(x * x + y * y)
        val t = ((x * dx + y * dy) / len2).coerceIn(0.0, 1.0)
        val ex = x - t * dx; val ey = y - t * dy
        return sqrt(ex * ex + ey * ey).let { if (it.isNaN()) abs(x) else it }
    }
}
