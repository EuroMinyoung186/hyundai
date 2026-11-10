package ai.moduui.jari.ui.components

import ai.moduui.jari.ui.theme.LocalPalette
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 차량 화면 기준 최소 터치 높이. 운전자가 한 번에 누를 수 있게 넉넉히. */
val TouchHeight: Dp = 64.dp

/** 넓으면 두 열, 좁으면(1 window 세로) 한 열로 쌓음 — 어떤 창 크기에서도 핵심 기능 사용 가능 */
@Composable
fun TwoPane(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 760.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.15f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { left() }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { right() }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                left()
                right()
            }
        }
    }
}

/** 디자인 카드 + 위쪽 작은 제목 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .fillMaxWidth()
            .designCard(p, 24.dp)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null || trailing != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title ?: "", color = p.sub, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (trailing != null) Text(trailing, color = p.accentText, fontSize = 13.sp)
            }
        }
        content()
    }
}

@Composable
fun Pill(text: String, color: Color? = null, filled: Boolean = false, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val c = color ?: p.chipText
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (filled) c else p.chip)
            .border(1.dp, if (filled) c else c.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = if (filled) p.onAccent else c, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 큰 버튼. primary = 청록 그라데이션, 아니면 어두운 보조 버튼 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    height: Dp = TouchHeight,
    sub: String? = null,
) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(18.dp)
    val fg = if (primary) p.onAccent else p.text
    Column(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(Brush.verticalGradient(if (primary) listOf(p.accent, p.accentDark) else listOf(p.ghostTop, p.ghostBottom)))
            .border(1.dp, if (primary) Color.Transparent else p.chipBorder, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2)
        if (sub != null) Text(sub, color = fg.copy(alpha = 0.75f), fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** 상태 점 + 글자 (색만으로 구분하지 않도록 항상 글자를 같이 씀) */
@Composable
fun StatusDot(color: Color, label: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text("  $label", color = p.sub, fontSize = 13.sp)
    }
}

@Composable
fun SquareButton(symbol: String, description: String, enabled: Boolean = true, size: Dp = 48.dp, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(p.ghostTop, p.ghostBottom)))
            .border(1.dp, p.chipBorder, RoundedCornerShape(12.dp))
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = p.text, fontSize = 22.sp)
    }
}

/** 작은 단계 선택 (− 값 +) */
@Composable
fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit, label: String, minusEnabled: Boolean = true, plusEnabled: Boolean = true) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SquareButton("−", "$label 줄이기", minusEnabled, 44.dp, onMinus)
        Text(value, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 2.dp))
        SquareButton("+", "$label 늘리기", plusEnabled, 44.dp, onPlus)
    }
}

@Composable
fun SegmentedChoice(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).background(p.chip).border(1.dp, p.chipBorder, RoundedCornerShape(999.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (i == selected) Brush.verticalGradient(listOf(p.accent, p.accentDark)) else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)))
                    .clickable(role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (i == selected) p.onAccent else p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, color = p.sub, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor ?: p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
    }
}

@Composable
fun Spacer8() = Box(Modifier.size(8.dp))

@Composable
fun VSpace(h: Dp) = Box(Modifier.height(h))
