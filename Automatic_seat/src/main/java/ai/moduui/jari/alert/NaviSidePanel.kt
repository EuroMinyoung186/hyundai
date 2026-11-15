package ai.moduui.jari.alert

import ai.moduui.core.model.BabyState
import ai.moduui.core.reststop.RestStopCandidate
import ai.moduui.jari.control.TripController
import ai.moduui.jari.control.UiState
import ai.moduui.jari.ui.components.MapStop
import ai.moduui.jari.ui.components.RouteMap
import androidx.compose.ui.draw.clipToBounds
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 주행 중 생긴 일 하나 (아기 잠듦 등). 오른쪽 위 알림 → 누르면 상세. sticky 면 저절로 사라지지 않음 (휴게소 정차 중 등) */
data class InfoEvent(val emoji: String, val title: String, val lines: List<String>, val at: LocalTime, val sticky: Boolean = false)

/**
 * 내비 위 오른쪽 (앱 화면이 가려져 있을 때만, 다른 앱 위에 그리기 권한).
 *  - 들를 곳 제안(수유 · 기저귀 · 울음): 실제 내비 화면처럼 보이는 오른쪽 패널. 줄을 누르면 상세, [경유]로 내비 경로 변경.
 *  - 그 밖의 일(잠듦 · 깸 · 노래 · 음량 등): 오른쪽 위 작은 알림(모두의 자리 디자인). 누르면 오른쪽 패널에 무엇을 했는지 상세.
 * 주행 중 화면 규칙: 큰 글씨 · 큰 버튼 · 글 입력 없음.
 */
class NaviSidePanel(
    context: Context,
    private val controller: TripController,
    /** 앱 화면(MainActivity · 시연 지도)이 보이는지. 보이면 숨김 */
    private val appVisible: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    /** 오른쪽 패널을 띄우거나 내렸을 때 (알림 카드 자리 다시 잡기). true = 떠 있음 */
    private val onChanged: (Boolean) -> Unit = {},
) {
    private val app = context.applicationContext
    private val wm = app.getSystemService(WindowManager::class.java)

    /** 지금 띄울 오른쪽 위 알림 (시연 화면 전환기도 이걸 보고 내비로 넘어감) */
    val toast = MutableStateFlow<InfoEvent?>(null)

    /** 알림을 눌러 연 상세 */
    val detail = MutableStateFlow<InfoEvent?>(null)

    private val panel = OverlayWin()
    private val note = OverlayWin()
    private var toastJob: Job? = null

    private enum class Mode { REST, DETAIL }

    fun start() {
        // 1) 주행 중 생긴 일을 알림으로 (아기 상태 · 안내 음성 · 노래 · 좌석 조치가 바뀔 때)
        scope.launch {
            var last: Sig? = null
            controller.ui.map { Sig.of(it) }.distinctUntilChanged().collect { now ->
                val before = last
                last = now
                if (before == null || !now.driving) return@collect
                // 같은 일로 여러 값이 같이 바뀌므로 잠깐 기다렸다가 한 번에
                delay(700)
                eventOf(before, Sig.of(controller.ui.value), controller.ui.value)?.let { toast.value = it }
            }
        }
        // 2) 오른쪽 패널: 들를 곳 제안 또는 알림 상세
        scope.launch {
            combine(controller.ui, appVisible, detail, controller.ask) { ui, visible, d, ask ->
                when {
                    visible -> null
                    // 출발 질문 카드가 떠 있는 동안은 패널을 띄우지 않음 (같은 자리에 겹침)
                    ask != null -> null
                    d != null -> Mode.DETAIL
                    offering(ui) -> Mode.REST
                    else -> null
                }
            }.distinctUntilChanged().collect { mode ->
                if (mode == null) {
                    if (panel.hide()) setOpen(false)
                } else {
                    // 패널 오른쪽 끝은 내비 오른쪽 버튼 줄([2D] · 음량 · 설정) 왼쪽에
                    panel.show(Gravity.TOP or Gravity.END, RIGHT_GAP, TOP, PANEL_W) {
                        val ui by controller.ui.collectAsState()
                        val d by detail.collectAsState()
                        val e = d
                        if (mode == Mode.DETAIL && e != null) EventDetail(e) { detail.value = null } else RestPanel(ui, controller)
                    }
                    setOpen(true)
                }
            }
        }
        // 3) 오른쪽 위 알림: 몇 초 뒤 저절로 사라짐
        scope.launch {
            combine(toast, appVisible) { t, visible -> if (visible) null else t }.distinctUntilChanged().collect { t ->
                toastJob?.cancel()
                if (t == null) {
                    note.hide()
                    return@collect
                }
                // 오른쪽 패널이 떠 있으면 그 왼쪽에
                val x = if (open) RIGHT_GAP + PANEL_W + 20 else RIGHT_GAP
                note.show(Gravity.TOP or Gravity.END, x, TOP, NOTE_W) {
                    EventNote(t) {
                        toast.value = null
                        detail.value = t
                    }
                }
                if (!t.sticky) toastJob = scope.launch {
                    delay(NOTE_MS)
                    if (toast.value === t) toast.value = null
                }
            }
        }
    }

    private fun setOpen(v: Boolean) {
        if (open == v) return
        open = v
        onChanged(v)
    }

    private fun offering(ui: UiState): Boolean = ui.restStops?.let { it.addedName == null && it.candidates.isNotEmpty() } == true

    /** 오버레이 창 하나 (Compose) */
    private inner class OverlayWin {
        private var view: ComposeView? = null
        private var owner: OverlayOwner? = null

        fun show(gravity: Int, xDp: Int, yDp: Int, wDp: Int, content: @Composable () -> Unit) {
            if (!Settings.canDrawOverlays(app)) return
            hide()
            val o = OverlayOwner().also { it.start() }
            val v = ComposeView(app).apply {
                setViewTreeLifecycleOwner(o)
                setViewTreeSavedStateRegistryOwner(o)
                setContent { content() }
            }
            val lp = WindowManager.LayoutParams(
                dp(wDp), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                this.gravity = gravity
                x = dp(xDp)
                y = dp(yDp)
            }
            runCatching { wm.addView(v, lp) }
                .onSuccess {
                    view = v
                    owner = o
                }
                .onFailure { Log.w(TAG, "addView failed", it); o.stop() }
        }

        /** 떠 있던 것을 내렸으면 true */
        fun hide(): Boolean {
            val v = view ?: return false
            runCatching { wm.removeView(v) }
            owner?.stop()
            view = null
            owner = null
            return true
        }
    }

    private fun dp(v: Int) = (v * app.resources.displayMetrics.density).toInt()

    /** 알림 거리: 이 값이 바뀌면 "무슨 일이 있었다" */
    private data class Sig(
        val driving: Boolean,
        val baby: BabyState,
        val quiet: Boolean,
        val music: String?,
        val comfort: List<String>,
        val care: BabyState?,
    ) {
        companion object {
            fun of(ui: UiState) = Sig(
                driving = ui.isDriving || ui.departed,
                baby = ui.baby,
                quiet = ui.quiet,
                music = ui.music?.labelKo,
                comfort = ui.comfort.map { it.labelKo },
                care = ui.careSuggestion,
            )
        }
    }

    private fun eventOf(before: Sig, now: Sig, ui: UiState): InfoEvent? {
        val name = ui.profile.nickname
        val care = now.care
        val (emoji, title) = when {
            now.baby != before.baby && now.baby != BabyState.UNKNOWN -> babyEmoji(now.baby) to "$name · ${now.baby.labelKo}"
            care != null && care != before.care -> "📷" to "카메라: $name ${care.labelKo}"
            now.music != before.music -> "🎵" to (now.music?.let { "$it 트는 중" } ?: "노래를 껐어요")
            now.quiet != before.quiet -> "🔈" to if (now.quiet) "안내 음성을 작게" else "안내 음성을 원래대로"
            now.comfort != before.comfort && now.comfort.isNotEmpty() -> "💺" to "좌석 조절 · ${now.comfort.last()}"
            else -> return null
        }
        return InfoEvent(emoji, title, detailLines(ui), LocalTime.now())
    }

    /** 상세: 모두의 자리가 지금 무엇을 해 두었는지 */
    private fun detailLines(ui: UiState): List<String> = buildList {
        if (ui.baby != BabyState.UNKNOWN) add("아기 상태 · ${ui.baby.labelKo} (${ui.babySource.labelKo})")
        add("안내 음성 · " + if (ui.quiet) "작게 (아기가 깨지 않게)" else "보통")
        add("노래 · " + (ui.music?.let { "${it.labelKo} 트는 중" } ?: "꺼짐"))
        ui.comfort.forEach { add("좌석 · ${it.labelKo} (${it.reasonKo})") }
        ui.minutesToFeed?.let { add(if (it >= 0) "다음 수유 · ${hm(it)} 뒤" else "수유 시간 · ${hm(-it)} 지남") }
        ui.lastVoice?.let { add("안내 · “$it”") }
    }

    companion object {
        private const val TAG = "NaviSidePanel"
        const val PANEL_W = 600
        const val NOTE_W = 560
        const val TOP = 92
        const val RIGHT_GAP = 160
        private const val NOTE_MS = 8_000L

        /** 오른쪽 패널이 떠 있는지. 떠 있으면 휴게소 알림 카드는 패널이 대신 보여 줌 */
        @Volatile var open: Boolean = false
            private set
    }
}

// ─────────────── 들를 곳: 실제 내비 화면처럼 (흰 카드 · 검은 머리띠 · 검은 버튼) ───────────────

private val NCard = Color(0xFFFFFFFF)
private val NHead = Color(0xFF111111)
private val NInk = Color(0xFF111111)
private val NSub = Color(0xFF6B7280)
private val NLine = Color(0xFFE5E7EB)
private val NSoft = Color(0xFFF3F4F6)
private val HM = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun RestPanel(ui: UiState, c: TripController) {
    val offer = ui.restStops ?: return
    var open by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(NCard).border(1.dp, NLine, RoundedCornerShape(4.dp))) {
        Row(Modifier.fillMaxWidth().background(NHead).height(88.dp).padding(start = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("경유 가능한 곳", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(Modifier.size(88.dp).clickable { c.onAlertAction(AlertActions.REST_DISMISS) }, contentAlignment = Alignment.Center) {
                Text("✕", color = Color.White, fontSize = 32.sp)
            }
        }
        Text(offer.reasonKo, color = NSub, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 26.dp, vertical = 16.dp), maxLines = 2)
        // 전체 경로 지도: 지금 위치(화살표) + 후보 번호 핀 → 어디로 갈지 보고 고름
        Box(Modifier.fillMaxWidth().height(380.dp).clipToBounds()) {
            RouteMap(
                here = ui.navi.current,
                destination = ui.navi.route?.destination,
                destinationName = ui.navi.route?.destinationName,
                path = ui.roadPath,
                heading = ui.roadHeading ?: 0f,
                stops = offer.candidates.take(3).mapIndexed { i, cand ->
                    MapStop(i + 1, cand.restArea.name.removeSuffix("휴게소"), cand.restArea.location, primary = i == 0)
                },
                modifier = Modifier.matchParentSize(),
            )
        }
        offer.candidates.take(3).forEachIndexed { i, cand ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(NLine))
            RestRow(i, cand, ui, expanded = open == i, onToggle = { open = if (open == i) null else i }) {
                c.onAlertAction(AlertActions.rest(i))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(NLine))
        Text(
            "🎙  Gleo에게 \"1번 휴게소 들러 줘\"라고 말해도 돼요",
            color = NSub, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 26.dp, vertical = 16.dp),
        )
        Box(
            Modifier.fillMaxWidth().heightIn(min = 84.dp).background(NSoft).clickable { c.onAlertAction(AlertActions.REST_DISMISS) },
            contentAlignment = Alignment.Center,
        ) { Text("괜찮아요", color = NInk, fontSize = 28.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun RestRow(i: Int, cand: RestStopCandidate, ui: UiState, expanded: Boolean, onToggle: () -> Unit, onGo: () -> Unit) {
    val r = cand.restArea
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(NHead), contentAlignment = Alignment.Center) {
                Text("${i + 1}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
                Text(r.name, color = NInk, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${"%.0f".format(cand.distanceKm)}km · 약 ${cand.etaMinutes}분" + if (r.hasNursingRoom) " · 🍼 수유실" else "",
                    color = NSub, fontSize = 24.sp,
                )
            }
            Box(
                Modifier.widthIn(min = 120.dp).height(72.dp).clip(RoundedCornerShape(4.dp)).background(NHead).clickable(onClick = onGo).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) { Text("경유", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        }
        if (expanded) {
            Column(Modifier.padding(start = 70.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r.address.isNotBlank()) Text(r.address, color = NInk, fontSize = 22.sp)
                Text("도착 예정 · ${ui.now.plusMinutes(cand.etaMinutes.toLong()).format(HM)}", color = NInk, fontSize = 22.sp)
                Text("경로에서 벗어나는 거리 · 약 ${"%.1f".format(cand.detourKm)}km", color = NSub, fontSize = 22.sp)
                if (!r.verified) Text("수유실 정보는 확인이 필요해요 (시연 데이터)", color = NSub, fontSize = 20.sp)
            }
        }
    }
}

// ─────────────── 그 밖의 일: 모두의 자리 디자인 (밤 톤 · 강조 #3EC5F0) ───────────────

private val ACard = Color(0xFF14181D)
private val ALine = Color(0xFF262D35)
private val AText = Color(0xFFF2F5F7)
private val ASub = Color(0xFF8B95A1)
private val AAccent = Color(0xFF3EC5F0)

@Composable
private fun EventNote(e: InfoEvent, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(ACard).border(2.dp, AAccent, RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(e.emoji, fontSize = 40.sp)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text("모두의 자리 · ${e.at.format(HM)}", color = AAccent, fontSize = 18.sp)
            Text(e.title, color = AText, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("눌러서 무엇을 했는지 보기", color = ASub, fontSize = 20.sp)
        }
        Text("›", color = AText, fontSize = 40.sp)
    }
}

@Composable
private fun EventDetail(e: InfoEvent, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(ACard).border(2.dp, AAccent, RoundedCornerShape(28.dp)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("모두의 자리 · ${e.at.format(HM)}", color = AAccent, fontSize = 20.sp)
        Text("${e.emoji}  ${e.title}", color = AText, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth().height(1.dp).background(ALine))
        Text("지금 이렇게 맞춰 두었어요", color = ASub, fontSize = 22.sp)
        e.lines.forEach { Text("•  $it", color = AText, fontSize = 26.sp) }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(RoundedCornerShape(20.dp)).background(AAccent).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("닫기", color = Color(0xFF0A0C0F), fontSize = 28.sp, fontWeight = FontWeight.Bold) }
    }
}

private fun babyEmoji(s: BabyState) = when (s) {
    BabyState.ASLEEP -> "😴"
    BabyState.DROWSY -> "🥱"
    BabyState.AWAKE -> "👀"
    BabyState.CRYING -> "😢"
    BabyState.HUNGRY -> "🍼"
    BabyState.UNKNOWN -> "👶"
}

private fun hm(min: Int): String = when {
    min >= 60 && min % 60 == 0 -> "${min / 60}시간"
    min >= 60 -> "${min / 60}시간 ${min % 60}분"
    else -> "${min}분"
}

/** 오버레이 창에 Compose 를 띄우려면 Lifecycle · SavedState 주인이 필요함 */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
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
