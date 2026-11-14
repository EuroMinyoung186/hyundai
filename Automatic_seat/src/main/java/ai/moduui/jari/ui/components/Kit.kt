package ai.moduui.jari.ui.components

import ai.moduui.jari.ui.theme.LocalPalette
import ai.moduui.jari.ui.theme.Montserrat
import ai.moduui.jari.ui.theme.Palette
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 디자인 원본의 24×24 선 아이콘 path */
object Ic {
    const val HOME = "M4 11l8-7 8 7v9h-5v-6H9v6H4z"
    const val SEAT = "M7 4v9h9l2 6M7 13l-2 6"
    const val DRIVE = "M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16M12 12l4-3"
    const val GEAR = "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1"
    const val MIC = "M9 3h6v11H9zM5 11a7 7 0 0 0 14 0M12 18v3"
    const val CUBE = "M12 3l8 4.5v9L12 21l-8-4.5v-9zM12 12l8-4.5M12 12v9M12 12L4 7.5"
    const val POWER = "M12 4v6M7 6.5a7 7 0 1 0 10 0"
    const val UNDO = "M9 14L4 9l5-5M4 9h10a5 5 0 0 1 0 10h-3"
    const val CHECK = "M5 12l5 5 9-10"
    const val REFRESH = "M20 12a8 8 0 1 1-2.3-5.6M20 4v5h-5"
    const val LOCK = "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2zM8 11V8a4 4 0 0 1 8 0v3"
    const val BACK = "M15 5l-7 7 7 7"
    const val BABY = "M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16M9 10h.01M15 10h.01M9 14.5a4 4 0 0 0 6 0"
}

/** 선 아이콘. stroke 는 24 단위 기준 (디자인의 stroke-width 와 같음) */
@Composable
fun LineIcon(d: String, color: Color, size: Dp = 24.dp, stroke: Float = 1.6f, modifier: Modifier = Modifier) {
    val path = remember(d) { PathParser().parsePathString(d).toPath() }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        translate((this.size.width - 24f * s) / 2f, (this.size.height - 24f * s) / 2f) {
            scale(s, s, pivot = Offset.Zero) {
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** 디자인 카드: 다크 = 160° 그라데이션 + 얇은 테두리, 일반 = 흰색 + 부드러운 그림자 */
fun Modifier.designCard(p: Palette, radius: Dp = 28.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .then(if (!p.isDark) Modifier.shadow(14.dp, shape, ambientColor = p.cardShadow, spotColor = p.cardShadow) else Modifier)
        .clip(shape)
        .background(Brush.verticalGradient(0f to p.cardTop, 0.7f to p.cardBottom))
        .border(1.dp, p.cardBorder, shape)
}

/** Montserrat 얇은 영문 제목 (WELCOME · BOARDING · SETTINGS) */
@Composable
fun TitleMont(text: String, size: TextUnit, modifier: Modifier = Modifier, color: Color? = null, spacing: Float = 0.12f) {
    Text(
        text,
        modifier = modifier,
        color = color ?: LocalPalette.current.text,
        fontFamily = Montserrat,
        fontWeight = FontWeight.ExtraLight,
        fontSize = size,
        letterSpacing = spacing.em,
        maxLines = 1,
    )
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val p = LocalPalette.current
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(p.chip)
            .border(1.dp, p.chipBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color ?: p.chipText, fontSize = 14.sp, maxLines = 1)
    }
}

/** 홈 아래 SEATS · CLIMATE · AUTO 알약 */
@Composable
fun StatPill(key: String, value: String, valueColor: Color? = null, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier
            .height(52.dp)
            .then(if (!p.isDark) Modifier.shadow(6.dp, RoundedCornerShape(999.dp), ambientColor = p.cardShadow, spotColor = p.cardShadow) else Modifier)
            .clip(RoundedCornerShape(999.dp))
            .background(if (p.isDark) p.chip else p.card)
            .border(1.dp, p.chipBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(key, color = p.sub, fontFamily = Montserrat, fontSize = 11.sp, letterSpacing = 0.18.em, maxLines = 1)
        Text(value, color = valueColor ?: p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 오른쪽 위 "● 실제 데이터 / ● 시뮬레이션" */
@Composable
fun ModeChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, color = color, fontSize = 13.sp, maxLines = 1)
    }
}

/** 원형 배경 (켜짐 = 청록 빛 + 링, 꺼짐 = 어두운 구) */
private fun Modifier.knob(p: Palette, on: Boolean, glow: Boolean): Modifier = this
    .drawBehind {
        val r = size.minDimension / 2f
        if (on && glow) {
            drawCircle(p.glow.copy(alpha = 0.08f), radius = r + 10.dp.toPx())
            drawCircle(p.glow.copy(alpha = 0.14f), radius = r + 4.dp.toPx())
        }
        drawCircle(
            Brush.radialGradient(
                listOf(if (on) p.knobOnTop else p.knobOffTop, if (on) p.knobOnBottom else p.knobOffBottom),
                center = Offset(size.width / 2f, size.height * 0.3f),
                radius = size.minDimension * 0.85f,
            ),
            radius = r,
        )
    }
    .border(1.dp, if (on) p.knobOnRing else p.knobOffRing, CircleShape)

/** 홈 "간편 자리 맞춤"의 둥근 버튼 (가운데 큰 버튼 = 지금 적용) */
@Composable
fun KnobButton(label: String, icon: String, onClick: () -> Unit, big: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val size = if (big) 108.dp else 76.dp
    Column(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(28.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(size).knob(p, on = big, glow = big), contentAlignment = Alignment.Center) {
            LineIcon(icon, if (big) p.accentText else p.chipText, size = if (big) 40.dp else 28.dp, stroke = 1.5f)
        }
        Text(label, color = p.chipText, fontSize = 14.sp, maxLines = 1)
    }
}

/** 왼쪽 레일의 탭 */
@Composable
fun RailTab(label: String, icon: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val fg = if (selected) p.accentText else p.muted
    Column(
        Modifier
            .width(72.dp)
            .height(80.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(36.dp))
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(48.dp).knob(p, on = selected, glow = selected), contentAlignment = Alignment.Center) {
            LineIcon(icon, fg, size = 22.dp)
        }
        Text(label, color = fg, fontSize = 13.sp, maxLines = 1)
    }
}

/** 작은 원형 아이콘 버튼 (회전 고정 · 다시 읽기) */
@Composable
fun CircleIconButton(icon: String, description: String, onClick: () -> Unit, selected: Boolean = false, size: Dp = 48.dp) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(size)
            .knob(p, on = selected, glow = false)
            .clip(CircleShape)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(icon, p.text, size = 20.dp, stroke = 2f)
    }
}

/** 청록 그라데이션 주 버튼 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    enabled: Boolean = true,
    icon: String? = null,
    fontSize: TextUnit = 18.sp,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (enabled) Modifier.shadow(10.dp, shape, ambientColor = p.glow.copy(alpha = 0.4f), spotColor = p.glow.copy(alpha = 0.4f)) else Modifier)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(p.accent, p.accentDark)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) LineIcon(icon, p.onAccent, size = 22.dp, stroke = 2f)
        Text(text, color = p.onAccent, fontSize = fontSize, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 어두운 보조 버튼 (다시 읽기 · 앞으로 · 뒤로 …) */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    enabled: Boolean = true,
    radius: Dp = 999.dp,
    fontSize: TextUnit = 14.sp,
    textColor: Color? = null,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(p.ghostTop, p.ghostBottom)))
            .border(1.dp, p.chipBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor ?: p.text, fontSize = fontSize, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** 디자인 토글 (60×34, 켜짐 = 청록 그라데이션) */
@Composable
fun DesignToggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true, description: String? = null) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .size(60.dp, 34.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (checked) Brush.verticalGradient(listOf(p.accent, p.accentDark)) else SolidColor(if (p.isDark) Color(0xFF2A2D33) else Color(0xFFD5DADF)))
            .semantics { if (description != null) contentDescription = description }
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        Box(
            Modifier
                .padding(start = if (checked) 29.dp else 3.dp, top = 3.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0xFFF1F4F7)),
        )
    }
}

/** 설정 카드 한 줄: 제목 · 설명 · 오른쪽 조작 */
@Composable
fun SettingRow(title: String, sub: String? = null, last: Boolean = false, trailing: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                if (sub != null) Text(sub, color = p.sub, fontSize = 13.sp)
            }
            trailing()
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(p.divider))
    }
}

/**
 * 디자인 "적용 · 누르고 있으면 맞춰요": 누르고 있는 동안만 좌석이 움직이고 손을 떼면 멈춤.
 * 스쳐 누른 것(0.25초 미만)은 무시함. 화면을 벗어나거나 버튼이 꺼져도 멈춤.
 */
@Composable
fun HoldApplyButton(
    idleText: String,
    holdingText: String,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    var holding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    DisposableEffect(Unit) {
        onDispose { if (holding) stop() }
    }
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .height(56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (enabled) Modifier.shadow(10.dp, shape, ambientColor = p.glow.copy(alpha = 0.4f), spotColor = p.glow.copy(alpha = 0.4f)) else Modifier)
            .clip(shape)
            .background(Brush.verticalGradient(if (holding) listOf(Color(0xFF7FD9F0), p.accent) else listOf(p.accent, p.accentDark)))
            .semantics {
                role = Role.Button
                contentDescription = "$idleText, 누르고 있는 동안만 움직여요"
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    var started = false
                    val job = scope.launch {
                        delay(250)
                        started = true
                        holding = true
                        start()
                    }
                    try {
                        tryAwaitRelease()
                    } finally {
                        job.cancel()
                        if (started) stop()
                        holding = false
                    }
                })
            }
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        LineIcon(Ic.POWER, p.onAccent, size = 22.dp, stroke = 2f)
        Text(if (holding) holdingText else idleText, color = p.onAccent, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
