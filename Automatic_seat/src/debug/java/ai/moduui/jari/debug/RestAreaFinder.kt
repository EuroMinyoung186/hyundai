package ai.moduui.jari.debug

import ai.moduui.core.model.GeoPoint
import ai.moduui.core.reststop.RestArea
import ai.moduui.core.route.RoutePath
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos

/**
 * 시연 전용 (debug): 경로 위 휴게소를 OpenStreetMap(Nominatim 검색)에서 찾음.
 * NaviHelper 가 경로 위 휴게소 목록을 주지 않아서, 목적지가 바뀔 때마다 경로를 몇 구간으로 나눠 "휴게소"를 검색함.
 *  - 경로에서 1km 안에 있는 것만
 *  - 우리 방향 쪽(진행 방향 오른쪽)만: 반대 방향 휴게소를 경유지로 넣으면 내비가 유턴 경로를 만듦
 *  - 같은 이름은 하나만 (가장 가까운 것)
 * 수유실 여부는 모르므로 있음(확인 필요, verified=false)으로 둠. Nominatim 사용 규칙대로 1초에 한 번만 물음.
 */
object RestAreaFinder {
    private const val TAG = "RestAreaFinder"
    private const val SEGMENT_KM = 40.0

    suspend fun find(path: RoutePath): List<RestArea> {
        val total = path.lengthKm
        if (total < 10) return emptyList()
        data class Hit(val base: String, val at: GeoPoint, val along: Double, val off: Double, val address: String)
        val hits = mutableListOf<Hit>()
        var startKm = 0.0
        while (startKm < total) {
            val endKm = (startKm + SEGMENT_KM).coerceAtMost(total)
            val pts = (0..8).map { path.pointAt(startKm + (endKm - startKm) * it / 8.0) }
            val pad = 0.02
            val box = "${pts.minOf { it.lng } - pad},${pts.maxOf { it.lat } + pad},${pts.maxOf { it.lng } + pad},${pts.minOf { it.lat } - pad}"
            for (e in search(box)) {
                val name = e.optString("name")
                if (!name.contains("휴게소")) continue
                val at = GeoPoint(e.getString("lat").toDouble(), e.getString("lon").toDouble())
                val (along, off) = path.locate(at)
                if (off > 1.0 || along < 5.0 || along > total - 5.0) continue
                if (!onRightSide(path, along, at)) continue
                hits += Hit(baseName(name), at, along, off, e.optString("display_name").split(",").take(4).joinToString(",").trim())
            }
            startKm = endKm
            delay(1_100) // Nominatim: 1초에 한 번
        }
        return hits.groupBy { it.base }.map { (base, list) -> list.minBy { it.off } }
            .sortedBy { it.along }
            .mapIndexed { i, h ->
                RestArea(
                    id = "route-$i-${h.base}",
                    name = h.base,
                    location = h.at,
                    hasNursingRoom = true,
                    address = h.address,
                    verified = false,
                )
            }
            .also { Log.i(TAG, "found ${it.size}: ${it.joinToString { a -> a.name }}") }
    }

    /** "경기광주(하)휴게소 명가의뜰" · "평창 휴게소 (강릉 방향)" → "경기광주휴게소" · "평창휴게소" */
    private fun baseName(name: String): String {
        val head = name.substringBefore("휴게소")
        val clean = head.replace(Regex("""\((상|하|[^)]*방향)\)"""), "").replace(Regex("""\S+방향"""), "").replace(" ", "").trim()
        return clean + "휴게소"
    }

    /** 진행 방향 오른쪽에 있는지 (우리나라는 오른쪽 통행 → 우리 방향 휴게소는 오른쪽) */
    private fun onRightSide(path: RoutePath, along: Double, p: GeoPoint): Boolean {
        val a = path.pointAt((along - 0.3).coerceAtLeast(0.0))
        val b = path.pointAt((along + 0.3).coerceAtMost(path.lengthKm))
        val k = cos(Math.toRadians(a.lat))
        val dx = (b.lng - a.lng) * k
        val dy = b.lat - a.lat
        val px = (p.lng - a.lng) * k
        val py = p.lat - a.lat
        return dx * py - dy * px < 0 // 외적이 음수 = 오른쪽
    }

    private fun search(viewbox: String): List<org.json.JSONObject> = runCatching {
        val q = URLEncoder.encode("휴게소", "UTF-8")
        val url = URL("https://nominatim.openstreetmap.org/search?q=$q&format=jsonv2&viewbox=$viewbox&bounded=1&limit=50&accept-language=ko")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "route-demo/1.0")
        val text = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        val arr = JSONArray(text)
        List(arr.length()) { arr.getJSONObject(it) }
    }.onFailure { Log.w(TAG, "search failed", it) }.getOrDefault(emptyList())
}
