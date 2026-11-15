package ai.moduui.jari.alert

import ai.moduui.jari.control.StartAsk
import ai.moduui.jari.control.TripController
import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * 출발 알림 (11/15): 내비 위 오른쪽 위 카드 하나로 그림 (매초 다시 띄우지 않아 깜빡이지 않음).
 *  1) "Baby Seat 기능을 사용하시겠습니까?" [예] [⑩ 아니요] — 아니요 버튼에 줄어드는 원 + 남은 초
 *  2) [예] → 마지막 수유 시각: 오전/오후 · 시 · 분 휠 + [취소] [확인]
 * 다른 앱 위에 그리기 권한이 있을 때만. 주행 중 규칙: 큰 글씨 · 큰 버튼(84dp) · 글 입력 없음.
 */
class StartAskCard(context: Context, private val controller: TripController, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val wm = app.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var owner: Owner? = null

    fun start() {
        scope.launch {
            // 단계가 바뀔 때만 창을 다시 만듦 (예 → 시각 고르기). 그 안의 값은 Compose 가 그림
            controller.ask.map { it?.javaClass }.distinctUntilChanged().collect { kind ->
                hide()
                if (kind != null) show()
            }
        }
    }

    private fun show() {
        if (!Settings.canDrawOverlays(app)) return
        val o = Owner().also { it.start() }
        val v = ComposeView(app).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent {
                val a = controller.ask.collectAsState().value
                when (a) {
                    is StartAsk.Use -> UseCard(a, { controller.answerUse(true) }, { controller.answerUse(false) })
                    is StartAsk.Pick -> PickCard(a, controller::confirmFeedPick, controller::cancelFeedPick)
                    null -> Unit
                }
            }
        }
        val d = app.resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            (720 * d).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (24 * d).toInt()
            y = (130 * d).toInt()
        }
        runCatching { wm.addView(v, lp) }
            .onSuccess {
                view = v
                owner = o
                // 맨 위 칸: 아래 알림들이 이 카드 밑으로 쌓이게 아래 끝을 알림
                v.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ -> if (view === v) TopRightStack.setAskBottom(lp.y + (bottom - top)) }
            }
            .onFailure { Log.w("StartAskCard", "addView failed", it); o.stop() }
    }

    private fun hide() {
        if (view != null) TopRightStack.setAskBottom(0)
        view?.let { runCatching { wm.removeView(it) } }
        owner?.stop()
        view = null
        owner = null
    }

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

// ─────────────── 화면 (앱 밤 톤 · 강조 #3EC5F0, 다른 내비 위 알림과 같은 모양) ───────────────

private val Card = Color(0xFF14181D)
private val Line = Color(0xFF262D35)
private val Text1 = Color(0xFFF2F5F7)
private val Sub = Color(0xFF8B95A1)
private val Accent = Color(0xFF3EC5F0)
private val AccentDeep = Color(0xFF1A9FD6)
private val Chip = Color(0xFF1C2228)

@Composable
private fun Frame(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Card).border(2.dp, Accent, RoundedCornerShape(28.dp))
            .padding(horizontal = 30.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Baby Seat", color = Accent, fontSize = 18.sp)
        content()
    }
}

@Composable
private fun UseCard(a: StartAsk.Use, onYes: () -> Unit, onNo: () -> Unit) {
    // 남은 시간 비율 1 → 0 (끊김 없이 줄어듦)
    val total = StartAsk.USE_SECONDS * 1000f
    val start = ((a.deadlineMs - SystemClock.elapsedRealtime()) / total).coerceIn(0f, 1f)
    val left = remember(a.deadlineMs) { Animatable(start) }
    LaunchedEffect(a.deadlineMs) {
        left.animateTo(0f, tween(durationMillis = (start * total).toInt(), easing = LinearEasing))
    }
    val secs = kotlin.math.ceil(left.value * StartAsk.USE_SECONDS).toInt().coerceAtLeast(0)
    Frame {
        Text("👶 Baby Seat 기능을 사용하시겠습니까?", color = Text1, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            (a.place?.let { "$it 가는 길 · " } ?: "") + "수유 · 밥 시간에 맞춰 휴게소를 찾아 드려요\n\"예\"라고 말하거나 눌러 주세요",
            color = Sub, fontSize = 22.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(Accent, AccentDeep))).clickable(onClick = onYes),
                contentAlignment = Alignment.Center,
            ) { Text("예", color = Color(0xFF0A0C0F), fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            // 아니요: 줄어드는 원 안에 남은 초 (0 이 되면 아니요)
            Row(
                Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(16.dp)).background(Chip)
                    .border(1.dp, Line, RoundedCornerShape(16.dp)).clickable(onClick = onNo),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = 4.dp.toPx()
                        val r = Size(size.width - w, size.height - w)
                        val o = Offset(w / 2, w / 2)
                        drawArc(Line, 0f, 360f, false, o, r, style = Stroke(w))
                        drawArc(Text1, -90f, 360f * left.value, false, o, r, style = Stroke(w, cap = StrokeCap.Round))
                    }
                    Text("$secs", color = Text1, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(14.dp))
                Text("아니요", color = Text1, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PickCard(a: StartAsk.Pick, onOk: (LocalTime) -> Unit, onCancel: () -> Unit) {
    val ampm = listOf("오전", "오후")
    val hours = (1..12).toList()
    val mins = (0..55 step 5).toList()
    val t0 = a.initial
    var ap by remember(t0) { mutableIntStateOf(if (t0.hour < 12) 0 else 1) }
    var h by remember(t0) { mutableIntStateOf(hours.indexOf(if (t0.hour % 12 == 0) 12 else t0.hour % 12)) }
    var m by remember(t0) { mutableIntStateOf(mins.indexOf(t0.minute / 5 * 5).coerceAtLeast(0)) }
    val picked = LocalTime.of((hours[h] % 12) + if (ap == 1) 12 else 0, mins[m])
    val next = picked.plusMinutes(a.intervalMin.toLong())
    Frame {
        Text("🍼 마지막 수유 시간", color = Text1, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            "수유 간격 ${a.intervalMin / 60}시간${if (a.intervalMin % 60 > 0) " ${a.intervalMin % 60}분" else ""} → 다음 수유 ${"%d:%02d".format(next.hour, next.minute)}쯤 · 30분 전에 수유실 휴게소를 찾아 드려요",
            color = Sub, fontSize = 20.sp,
        )
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            // 가운데 고른 줄 표시 (세 휠 공통)
            Box(
                Modifier.fillMaxWidth().height(ROW).align(Alignment.Center).clip(RoundedCornerShape(14.dp))
                    .background(Accent.copy(alpha = 0.12f)).border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
            )
            Row(Modifier.fillMaxWidth()) {
                Wheel(ampm, ap, { ap = it }, Modifier.weight(1f))
                Wheel(hours.map { "$it" }, h, { h = it }, Modifier.weight(1f))
                Wheel(mins.map { "%02d".format(it) }, m, { m = it }, Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(16.dp)).background(Chip)
                    .border(1.dp, Line, RoundedCornerShape(16.dp)).clickable(onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) { Text("취소", color = Text1, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            Box(
                Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(Accent, AccentDeep))).clickable { onOk(picked) },
                contentAlignment = Alignment.Center,
            ) { Text("확인", color = Color(0xFF0A0C0F), fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

private val ROW = 64.dp

/** 위아래로 돌리는 휠 (3줄 보임, 가운데가 고른 값). 줄을 눌러도 그 값으로 감 */
@Composable
private fun Wheel(options: List<String>, selected: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val scope = rememberCoroutineScope()
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    val center by remember {
        derivedStateOf { (state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0).coerceIn(0, options.lastIndex) }
    }
    LaunchedEffect(center) { if (center != selected) onSelected(center) }
    LazyColumn(state = state, flingBehavior = fling, contentPadding = PaddingValues(vertical = ROW), modifier = modifier.height(ROW * 3)) {
        items(options.size) { i ->
            val on = i == center
            Box(
                Modifier.fillMaxWidth().height(ROW).clickable { scope.launch { state.animateScrollToItem(i) } },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    options[i],
                    color = if (on) Accent else Text1,
                    fontSize = if (on) 32.sp else 24.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.alpha(if (on) 1f else 0.45f),
                )
            }
        }
    }
}
