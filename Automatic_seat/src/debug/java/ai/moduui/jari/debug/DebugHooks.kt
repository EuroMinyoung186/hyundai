package ai.moduui.jari.debug

import ai.moduui.jari.ModuuiApp
import kotlinx.coroutines.launch

/**
 * debug 빌드에서 앱이 켜질 때 부름 (ModuuiApp 이 리플렉션으로 찾음, release 에는 없음).
 * 시연 버튼(처음부터 · P · D)을 띄우고, 화면 전환 · 도로 주행을 켜 둠 (경로를 정하고 D 로 바꾸면 시작).
 */
object DebugHooks {
    @JvmStatic
    fun onAppStart(app: ModuuiApp) {
        val graph = app.graph
        graph.scope.launch {
            // 시연 지도 창을 먼저 만들고 버튼을 나중에 → 버튼이 지도 위에 옴
            DemoDirector.start(app, graph)
            DemoReset.ensure(app, graph)
            RouteFetcher.start(graph)
            DemoDrive.start(graph.scope, graph.controller, kmh = 100.0, speedup = DemoDrive.SPEEDUP, fromKm = 0.0)
        }
    }
}
