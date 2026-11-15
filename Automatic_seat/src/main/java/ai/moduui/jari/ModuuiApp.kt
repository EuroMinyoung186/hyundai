package ai.moduui.jari

import android.app.Application

class ModuuiApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // 콜드 스타트에서는 최소한만 초기화 (Pleos 성능 가이드). SDK 연결은 비동기 콜백으로 진행됨.
        graph = AppGraph(this)
        graph.controller.start()
        graph.phoneLink?.start()
        graph.naviPanel.start()
        graph.startAsk.start()
        // debug 빌드만: 시연 버튼 · 화면 전환 (src/debug 의 DebugHooks, release 에는 없음)
        if (BuildConfig.DEBUG) {
            runCatching {
                Class.forName("ai.moduui.jari.debug.DebugHooks").getMethod("onAppStart", ModuuiApp::class.java).invoke(null, this)
            }
        }
    }
}
