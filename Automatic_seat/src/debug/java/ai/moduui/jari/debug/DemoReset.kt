package ai.moduui.jari.debug

import ai.moduui.jari.AppGraph
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 시연 전용 (debug): 내비 화면 아래 가운데에 늘 떠 있는 [↺ 처음부터] [P 정차] [D 출발] 버튼.
 * P · D 는 가상 콘솔 기어(D 는 100km/h)를 바꿈.
 * 누르면 주행 · 경로 · 제안 · 알림을 모두 지우고 차를 P · 0km/h 로, 아기는 깸 · 수유 1시간 전으로 돌린 뒤
 * 내비 "어디로 갈까요?" 화면을 띄움 → 목적지를 정하고 D 로 바꾸면 처음부터 다시 시작.
 *
 *   adb shell am broadcast ... --es cmd demoReset
 */
object DemoReset {
    private var added = false

    fun ensure(context: Context, graph: AppGraph) {
        if (added) return
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return
        val wm = app.getSystemService(WindowManager::class.java)
        val d = app.resources.displayMetrics.density
        fun btn(label: String, color: Int, onClick: () -> Unit) = TextView(app).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding((22 * d).toInt(), (10 * d).toInt(), (22 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 30 * d
                setColor(color)
            }
            setOnClickListener { onClick() }
        }
        val b = android.widget.LinearLayout(app).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            val gap = android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (12 * d).toInt() }
            addView(btn("↺  처음부터", Color.rgb(17, 17, 17)) { reset(app, graph) }, gap)
            addView(btn("P  정차", Color.rgb(75, 85, 99)) { graph.scope.launch(Dispatchers.IO) { Console.park() } }, gap)
            addView(btn("D  출발", Color.rgb(24, 119, 242)) { graph.scope.launch(Dispatchers.IO) { Console.drive() } })
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 위 상태 줄은 시스템 창이 덮으므로, 내비 화면 아래 가운데 빈 곳(도착 카드 오른쪽 · 음량 버튼 왼쪽)에
            gravity = Gravity.TOP or Gravity.START
            x = (1500 * d).toInt()
            y = (1210 * d).toInt()
        }
        runCatching { wm.addView(b, lp) }
            .onSuccess { added = true }
            .onFailure { Log.w(TAG, "addView failed", it) }
    }

    fun reset(context: Context, graph: AppGraph) {
        val app = context.applicationContext
        val c = graph.controller
        Log.i(TAG, "reset")
        DemoDrive.stop(c)
        DemoDirector.stop()
        NaviMapLayer.rerouted.value = null
        graph.naviPanel.toast.value = null
        graph.naviPanel.detail.value = null
        c.resetDemo()
        graph.scope.launch {
            withContext(Dispatchers.IO) { Console.reset() }
            // 내비 경로 취소가 끝난 뒤 다시 켜기: 새 목적지를 정하면 주행이 그 경로를 기다렸다가 시작함
            delay(2_000)
            runCatching {
                app.packageManager.getLaunchIntentForPackage(NAVI_PKG)?.let {
                    app.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
            DemoDirector.start(app, graph)
            DemoDrive.start(graph.scope, c, kmh = 100.0, speedup = DemoDrive.SPEEDUP, fromKm = 0.0)
        }
    }

    /** 가상 콘솔: P · 0km/h · 문 닫힘 */
    private object Console {
        private const val BASE = "http://127.0.0.1:8088"
        fun park() {
            req("POST", "$BASE/browser/session?client=everyseat-sim")
            req("PUT", "$BASE/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0")
            req("PUT", "$BASE/vehicle/gear?gear=Park")
        }
        fun drive() {
            req("POST", "$BASE/browser/session?client=everyseat-sim")
            req("PUT", "$BASE/vehicle/gear?gear=Drive")
            req("PUT", "$BASE/vehicle/controls?speed=100&accelerator=33&brake=0&steering=0")
        }
        fun reset() {
            req("POST", "$BASE/browser/session?client=everyseat-sim")
            req("PUT", "$BASE/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0")
            req("PUT", "$BASE/vehicle/gear?gear=Park")
            req("PUT", "$BASE/vehicle/body?doors_locked=false")
        }
        private fun req(method: String, url: String) = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 800
            c.readTimeout = 800
            try { c.responseCode } finally { c.disconnect() }
        }
    }

    private const val TAG = "DemoReset"
    private const val NAVI_PKG = "ai.umos.maps.android.navigation.app"
}
