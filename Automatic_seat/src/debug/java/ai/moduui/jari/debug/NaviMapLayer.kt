package ai.moduui.jari.debug

import ai.moduui.jari.control.TripController
import ai.moduui.jari.ui.components.MapStop
import ai.moduui.jari.ui.components.RouteMap
import ai.moduui.jari.control.UiState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 시연 전용 (debug): Pleos 내비 화면의 빈 지도 자리에 실제 지도 + 내비 모양 파란 화살표를 깔아 차가 달리는 것처럼 보여 줌.
 * 에뮬레이터 내비는 GPS(NMEA 체크섬)를 버려서 화살표가 움직이지 않고, 지도 바탕도 비어 있음.
 *
 * - 내비 화면 전체를 덮고, 안내판 · 도착 카드는 NaviHelper 에서 받은 값(목적지 · 남은 시간 · 경유지)과 주행 위치로 직접 그림.
 *   (11/15 사용자 결정: 에뮬레이터 내비 화면은 시연에 쓰지 않고 이 지도 한 화면으로. 경로 · 경유지 변경은 계속 NaviHelper)
 * - 터치를 내비로 넘기지 않음: 안드로이드 12+는 터치를 넘기는(NOT_TOUCHABLE) 오버레이를 강제로 반투명(alpha 0.8)으로 만들어
 *   아래 내비의 고정 화살표가 비쳐 보임(11/15 확인). 지도가 떠 있는 동안 내비 버튼은 안 눌리고,
 *   경유지 변경 · 도착 장면에서는 지도를 걷으므로 그때 내비를 쓸 수 있음. 오른쪽 패널 · 알림은 따로 창이라 눌림.
 * - 창은 한 번만 만들고 보이기/숨기기만 함 → 나중에 뜨는 패널 · 알림이 늘 이 지도 위에 옴.
 * 좌표는 Pleos 에뮬레이터(2560x1440, 160dpi) 내비 안내 화면 기준 (11/15 화면에서 잼).
 */
object NaviMapLayer {
    val visible = MutableStateFlow(false)

    /** 경유지를 넣어 내비가 경로를 다시 찾았음 등 → 지도 위 잠깐 안내 */
    val rerouted = MutableStateFlow<String?>(null)
    private var view: ComposeView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null

    /** 내비 지도 자리 (화면 픽셀): 왼쪽 운전 정보 화면 오른쪽 ~ 오른쪽 손잡이 왼쪽, 위 상태 줄 아래 ~ 아래 독 위 */
    private const val LEFT = 853
    private const val TOP = 90
    private const val RIGHT = 2535
    private const val BOTTOM = 1300


    fun ensure(context: Context, c: TripController) {
        if (view != null) return
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "overlay not allowed")
            return
        }
        val wm = app.getSystemService(WindowManager::class.java)
        this.wm = wm
        val o = Owner().also { it.start() }
        val v = ComposeView(app).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                val show by visible.collectAsState()
                if (!show) return@setContent
                val ui by c.ui.collectAsState()
                val offer = ui.restStops
                val stop = offer?.addedName?.let { n -> offer.candidates.firstOrNull { it.restArea.name == n } }
                val pr by DemoDrive.progress.collectAsState()
                val re by rerouted.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    RouteMap(
                        here = ui.navi.current,
                        destination = ui.navi.route?.destination,
                        destinationName = ui.navi.route?.destinationName,
                        path = ui.roadPath,
                        done = ui.roadDone,
                        heading = ui.roadHeading ?: 0f,
                        stops = listOfNotNull(stop?.let { MapStop(1, "${it.restArea.name.removeSuffix("휴게소")} · 경유", it.restArea.location, primary = true) }),
                        modifier = Modifier.fillMaxSize(),
                    )
                    NaviCards(ui, pr, re)
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            RIGHT - LEFT, BOTTOM - TOP,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 처음엔 숨김 상태: 터치를 내비로 넘김
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = LEFT
            y = TOP
        }
        runCatching { wm.addView(v, lp) }
            .onSuccess {
                view = v
                this.lp = lp
                setTouchable(visible.value)
            }
            .onFailure { Log.w(TAG, "addView failed", it); o.stop() }
    }

    fun show() {
        visible.value = true
        setTouchable(true)
    }

    fun hide() {
        visible.value = false
        setTouchable(false)
    }

    /** 지도가 보일 때는 터치를 받아 불투명하게, 숨길 때는 터치를 내비로 넘김 */
    private fun setTouchable(on: Boolean) {
        val v = view ?: return
        val p = lp ?: return
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.alpha = 1f
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private const val TAG = "NaviMapLayer"

    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        fun start() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}

// ─────────────── 지도 위 안내 카드 (내비 모양: 검은 안내판 · 흰 도착 카드) ───────────────

@Composable
private fun NaviCards(ui: UiState, pr: DemoProgress?, rerouted: String?) {
    val kmh = 100.0
    val dest = ui.navi.route?.destinationName ?: pr?.destination ?: "목적지"
    val stopName = pr?.stopName
    val stopKm = pr?.stopKm
    val stopLeft = if (pr != null && stopKm != null && !pr.stopped) (stopKm - pr.km).coerceAtLeast(0.0) else null
    val destLeft = pr?.let { (it.totalKm - it.km).coerceAtLeast(0.0) } ?: ui.navi.route?.remainMeters?.div(1000.0)
    val nextName = if (stopLeft != null) stopName ?: "경유지" else dest
    val nextKm = stopLeft ?: destLeft
    val stopped = pr?.stopped == true
    Box(Modifier.fillMaxSize().padding(start = 18.dp, top = 18.dp, bottom = 18.dp)) {
        // 왼쪽 위: 안내판 (다음에 들를 곳 방면 · 남은 거리)
        Row(
            Modifier.width(560.dp).background(Color(0xFF111111)).padding(horizontal = 28.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (stopped) "🅿" else "⬆", color = Color.White, fontSize = 60.sp)
            Column(Modifier.padding(start = 24.dp)) {
                Text(
                    when {
                        stopped -> "정차 중"
                        nextKm != null -> km(nextKm)
                        else -> "안내 중"
                    },
                    color = Color.White, fontSize = 50.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (stopped) "${stopName ?: "휴게소"} · D로 바꾸면 출발해요" else "$nextName 방면",
                    color = Color(0xFFD1D5DB), fontSize = 24.sp, maxLines = 1,
                )
            }
        }
        // 경로를 다시 찾았을 때 (경유지 추가) · 도착
        if (rerouted != null) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 200.dp)
                    .background(Color(0xFF111111), RoundedCornerShape(36.dp)).padding(horizontal = 36.dp, vertical = 20.dp),
            ) {
                Text("🔄  $rerouted", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // 왼쪽 아래: 도착 카드 (내비 모양)
        if (destLeft != null) {
            val destSec = ui.navi.route?.remainSeconds?.toDouble() ?: (destLeft / kmh * 3600)
            val shownSec = if (stopLeft != null) stopLeft / kmh * 3600 else destSec
            Column(
                Modifier.align(Alignment.BottomStart).width(560.dp)
                    .background(Color.White).border(1.dp, Color(0xFFE5E7EB)).padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                Row {
                    Text(
                        ui.now.plusSeconds(shownSec.toLong()).format(DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)),
                        color = Color(0xFF111111), fontSize = 30.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${hm((shownSec / 60).toInt())} · ${km(nextKm ?: destLeft)}", color = Color(0xFF4B5563), fontSize = 24.sp)
                }
                Text(nextName, color = Color(0xFF4B5563), fontSize = 24.sp, modifier = Modifier.padding(top = 6.dp))
                if (stopLeft != null) {
                    Text("이후 $dest · ${km(destLeft)}", color = Color(0xFF9CA3AF), fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (pr != null) {
                    Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(8.dp).background(Color(0xFFE5E7EB))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth((pr.km / pr.totalKm).toFloat().coerceIn(0f, 1f)).background(Color(0xFF1877F2)))
                    }
                }
            }
        }
    }
}

private fun km(v: Double): String = if (v < 1.0) "${(v * 1000).toInt()}m" else if (v < 10) "%.1fkm".format(v) else "${v.toInt()}km"

private fun hm(min: Int): String = if (min >= 60) "${min / 60}시간 ${min % 60}분" else "${min}분"
