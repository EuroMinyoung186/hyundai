package ai.moduui.jari.navi

import ai.moduui.core.model.GeoPoint
import ai.moduui.core.route.RoutePath
import android.content.Context
import org.json.JSONObject

/**
 * 시연용 실제 도로 경로 (assets/demo_route_*.json).
 * Pleos 에뮬레이터 내비는 위치가 고정이고 경로 모양을 주지 않아서, 앱 지도에 도로를 따라 그리고 차를 움직이는 데 씀.
 * 출발 · 목적지가 그 경로와 맞을 때만 씀 (다른 여정에는 지금처럼 개략선).
 */
class DemoRoutes(private val context: Context) {
    private val routes: List<RoutePath> by lazy {
        runCatching {
            context.assets.list("")!!.filter { it.startsWith("demo_route_") && it.endsWith(".json") }.map { name ->
                val pts = JSONObject(context.assets.open(name).bufferedReader().use { it.readText() }).getJSONArray("points")
                RoutePath(List(pts.length()) { i -> pts.getJSONArray(i).let { GeoPoint(it.getDouble(0), it.getDouble(1)) } })
            }
        }.getOrDefault(emptyList())
    }

    /** 실행 중에 받아 온 도로 경로 (debug 시연: 내비 목적지가 바뀌면 받아 옴). 최근 것이 먼저 */
    private val fetched = java.util.concurrent.CopyOnWriteArrayList<RoutePath>()

    fun add(path: RoutePath) {
        if (path.points.size >= 2) fetched.add(0, path)
    }

    /** 출발 · 목적지에 맞는 도로 경로 (없으면 null) */
    fun match(from: GeoPoint?, to: GeoPoint?): RoutePath? =
        fetched.firstOrNull { it.matches(from, to) } ?: routes.firstOrNull { it.matches(from, to) }
}
