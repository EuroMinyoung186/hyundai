package ai.moduui.jari.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** 디자인 "다크 모드 · 휴대폰" 색 */
object C {
    val bg = Color(0xFF0A0C0F)
    val card = Color(0xFF14181D)
    val cardHi = Color(0xFF0F2229)
    val line = Color(0xFF262D35)
    val text = Color(0xFFF2F5F7)
    val sub = Color(0xFF8B95A1)
    val accent = Color(0xFF3EC5F0)
    val accentDeep = Color(0xFF1A9FD6)
    val onAccent = Color(0xFF06222E)
    val ok = Color(0xFF3DD68C)
    val warn = Color(0xFFF5B841)
    val danger = Color(0xFFFF6B6B)
}

@Composable
private fun TitleThin(text: String, size: TextUnit = 30.sp) {
    Text(text, color = C.text, fontSize = size, fontWeight = FontWeight.Light, letterSpacing = 5.sp)
}

@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    val bg = if (enabled) Brush.horizontalGradient(listOf(C.accent, C.accentDeep)) else SolidColor(Color(0xFF232A31))
    Row(
        modifier
            .height(56.dp)
            .clip(shape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text, color = if (enabled) C.onAccent else C.sub, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 디자인의 어두운 둥근 버튼 ([QR로 연결하기]) */
@Composable
private fun DarkButton(text: String, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(Color(0xFF1A1F25))
            .border(1.dp, C.line, shape)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Text(text, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** P-2 위쪽: 뒤로 · 단계 표시(3칸) · 오른쪽 작은 글씨 */
@Composable
private fun StepBar(step: Int, right: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF1C2228)).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Text("‹", color = C.text, fontSize = 24.sp) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            repeat(3) { i ->
                Box(Modifier.width(22.dp).height(4.dp).clip(CircleShape).background(if (i < step) C.accent else C.line))
            }
        }
        Text(right, color = C.sub, fontSize = 13.sp, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun SmallButton(text: String, modifier: Modifier = Modifier, filled: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val base = modifier.clip(shape)
    Box(
        (if (filled) base.background(Brush.horizontalGradient(listOf(C.accent, C.accentDeep))) else base.background(Color(0xFF1C2228)).border(1.dp, C.line, shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (filled) C.onAccent else C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, highlight: Boolean = false, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (highlight) C.cardHi else C.card)
            .border(1.dp, if (highlight) C.accent.copy(alpha = 0.45f) else C.line, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

/** 디자인의 둥근 로고: 빛나는 원 안의 좌석 옆모습 */
@Composable
private fun Logo(size: Dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(C.accent.copy(alpha = 0.35f), Color.Transparent), radius = r), r)
        drawCircle(Color(0xFF0B1418), r * 0.72f)
        drawCircle(C.accent.copy(alpha = 0.8f), r * 0.72f, style = Stroke(width = 2.dp.toPx()))
        val u = r / 50f
        val c = Offset(r, r)
        val p = Path().apply {
            moveTo(c.x - 12 * u, c.y - 22 * u)
            lineTo(c.x - 12 * u, c.y + 10 * u)
            lineTo(c.x + 14 * u, c.y + 10 * u)
            lineTo(c.x + 18 * u, c.y + 22 * u)
            moveTo(c.x - 12 * u, c.y + 10 * u)
            lineTo(c.x - 12 * u, c.y + 22 * u)
        }
        drawPath(p, Color(0xFF8FE0F7), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ───────────────────────────── P-1 시작 ─────────────────────────────

private const val TERMS = "모두의 자리는 시연용 초기 버전이에요.\n\n" +
    "· 좌석은 차 화면에서 정차(P) · 5초 카운트다운 · 앉은 사람 · 문 열림을 확인한 뒤에만 움직여요.\n" +
    "· 아기 상태 알림과 휴게소 제안은 운전을 돕는 참고 정보예요. 아기는 보호자가 직접 확인해 주세요."

private const val PRIVACY = "· 휴대폰에서 넣은 목적지 · 인원 · 짐 · 아기 상태는 연결한 차로만 보내요.\n" +
    "· 시연용 중계 서버는 내용을 메모리에만 두고, 끄면 지워져요.\n" +
    "· 계정 로그인 없이 써요. 이 휴대폰과 차는 차 화면의 6자리 코드로만 이어져요."

@Composable
fun StartScreen(onStart: () -> Unit) {
    var doc by remember { mutableStateOf<Pair<String, String>?>(null) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.45f))
        Logo(200.dp)
        Spacer(Modifier.height(24.dp))
        Text("EVERY SEAT", color = C.text, fontSize = 28.sp, fontWeight = FontWeight.Light, letterSpacing = 9.sp)
        Spacer(Modifier.height(8.dp))
        Text("모두의 자리", color = C.sub, fontSize = 14.sp, letterSpacing = 2.sp)
        Spacer(Modifier.weight(1f))
        Text("아기 자리는 미리,", color = C.text, fontSize = 20.sp)
        Text("운전은 편하게.", color = C.accent, fontSize = 20.sp)
        Spacer(Modifier.height(26.dp))
        PrimaryButton("시작하기", onClick = onStart)
        Spacer(Modifier.height(16.dp))
        Row {
            Text("[약관]", color = C.sub, fontSize = 12.sp, modifier = Modifier.clickable { doc = "약관" to TERMS })
            Text("  ·  ", color = C.sub, fontSize = 12.sp)
            Text("[개인정보 처리방침]", color = C.sub, fontSize = 12.sp, modifier = Modifier.clickable { doc = "개인정보 처리방침" to PRIVACY })
        }
    }
    doc?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { doc = null },
            confirmButton = { TextButton(onClick = { doc = null }) { Text("확인", color = C.accent) } },
            title = { Text(title, color = C.text) },
            text = { Text(body, color = C.sub, fontSize = 14.sp) },
            containerColor = C.card,
        )
    }
}

// ───────────────────────────── P-2 차 연결 ─────────────────────────────

/** "차 화면 예시": 차 화면 오른쪽 위에 이렇게 코드와 QR이 보인다는 그림 */
@Composable
private fun CarExampleCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(C.card)
            .border(1.dp, C.line, RoundedCornerShape(22.dp))
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF101419))
                .border(1.dp, C.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("482 913", color = Color(0xFF7FD3EE), fontSize = 26.sp, fontWeight = FontWeight.Light, letterSpacing = 3.sp)
            QrMark(40.dp)
        }
        Text("차 화면 예시", color = C.sub, fontSize = 12.sp)
    }
}

/** QR 모양 그림 (예시용, 실제 QR 아님) */
@Composable
private fun QrMark(size: Dp) {
    Canvas(Modifier.size(size).clip(RoundedCornerShape(5.dp)).background(Color(0xFFE9EDF0))) {
        val n = 9
        val cell = this.size.width / n
        for (y in 0 until n) for (x in 0 until n) {
            if ((x * 7 + y * 11 + x * y) % 3 == 0) {
                drawRect(Color(0xFF1B1F24), Offset(x * cell, y * cell), androidx.compose.ui.geometry.Size(cell, cell))
            }
        }
    }
}

/** [QR로 연결하기] 앞의 네 모서리 아이콘 */
@Composable
private fun ScanIcon(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val k = w * 0.32f
        val sw = 2.dp.toPx()
        for ((cx, cy) in listOf(0f to 0f, w to 0f, 0f to w, w to w)) {
            val dx = if (cx == 0f) k else -k
            val dy = if (cy == 0f) k else -k
            drawLine(C.accent, Offset(cx, cy), Offset(cx + dx, cy), sw, StrokeCap.Round)
            drawLine(C.accent, Offset(cx, cy), Offset(cx, cy + dy), sw, StrokeCap.Round)
        }
    }
}

@Composable
fun ConnectScreen(ui: PhoneUi, vm: PhoneVm, scanQr: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // 칸을 누르면 그 숫자를 골라 둠 → 새 숫자를 치면 그 자리만 바뀜 (QR 로 채워지면 끝으로)
    var field by remember { mutableStateOf(TextFieldValue(ui.codeInput, TextRange(ui.codeInput.length))) }
    LaunchedEffect(ui.codeInput) {
        if (field.text != ui.codeInput) field = TextFieldValue(ui.codeInput, TextRange(ui.codeInput.length))
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        StepBar(step = 2, right = "코드", onBack = vm::backToStart)
        Spacer(Modifier.height(26.dp))
        TitleThin("CONNECT", 32.sp)
        Spacer(Modifier.height(14.dp))
        Row {
            Text("차 화면에서 ", color = C.text, fontSize = 15.sp)
            Text("모두의 자리", color = C.accent, fontSize = 15.sp)
            Text("를 열면", color = C.text, fontSize = 15.sp)
        }

        Text("6자리 연결 코드가 보여요.", color = C.text, fontSize = 15.sp)
        Spacer(Modifier.height(20.dp))
        CarExampleCard()
        Spacer(Modifier.height(18.dp))
        BasicTextField(
            value = field,
            onValueChange = { v ->
                val digits = v.text.filter(Char::isDigit).take(6)
                val cursor = v.selection.end.coerceIn(0, digits.length)
                // 6자리가 다 찼는데 칸 하나를 골라 고친 경우: 고른 자리만 바꾸고 다음 칸을 고름
                field = if (digits.length == 6 && cursor < 6 && v.selection.collapsed) {
                    TextFieldValue(digits, TextRange(cursor, cursor + 1))
                } else {
                    TextFieldValue(digits, TextRange(cursor))
                }
                vm.setCodeInput(digits)
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            decorationBox = { inner ->
                Box {
                    Box(Modifier.size(1.dp)) { inner() }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(6) { i ->
                            val ch = field.text.getOrNull(i)
                            val active = i == field.selection.start.coerceAtMost(5)
                            val shape = RoundedCornerShape(14.dp)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(0.82f)
                                    .clip(shape)
                                    .background(C.card)
                                    .border(if (active) 1.5.dp else 1.dp, if (active) C.accent else C.line, shape)
                                    .clickable {
                                        // 누른 칸 고르기: 숫자가 있으면 그 숫자를 골라 두고, 비어 있으면 첫 빈칸으로
                                        val len = field.text.length
                                        field = if (i < len) field.copy(selection = TextRange(i, i + 1)) else field.copy(selection = TextRange(len))
                                        runCatching { focus.requestFocus() }
                                        keyboard?.show()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(ch?.toString() ?: "", color = if (active && ch != null) C.accent else C.text, fontSize = 24.sp)
                            }
                        }
                    }
                }
            },
        )
        ui.connectError?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = C.danger, fontSize = 13.sp)
        }
        Spacer(Modifier.weight(1f))
        DarkButton("QR로 연결하기", leading = { ScanIcon(18.dp) }, onClick = scanQr)
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            if (ui.connecting) "연결하는 중…" else "연결하기",
            enabled = ui.codeInput.length == 6 && !ui.connecting,
            onClick = vm::connect,
        )
    }
}

// ───────────────────────────── P-2 연결됨 · 기능 동의 ─────────────────────────────

@Composable
fun LinkedScreen(ui: PhoneUi, vm: PhoneVm) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
        StepBar(step = 3, right = "동의", onBack = vm::backToCode)
        Spacer(Modifier.height(26.dp))
        TitleThin("CONNECT", 32.sp)
        Spacer(Modifier.height(14.dp))
        Text("차와 연결됐어요", color = C.accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("이제 휴대폰에서 여정을 보내면 차가 자리를 준비해요.", color = C.text, fontSize = 15.sp)
        Spacer(Modifier.height(20.dp))
        Card {
            CheckLine("차 확인", "코드 ${ui.code?.chunked(3)?.joinToString(" ").orEmpty()}")
            CheckLine("휴대폰 연결", "연결됨")
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (ui.agreed) C.cardHi else C.card)
                .border(1.dp, if (ui.agreed) C.accent.copy(alpha = 0.6f) else C.line, RoundedCornerShape(20.dp))
                .clickable { vm.setAgreed(!ui.agreed) }
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CheckCircle(ui.agreed)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("목적지 · 인원 · 짐 · 아기 상태를 이 차로 보내는 데 동의해요", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("좌석은 차 화면에서 안전을 확인한 뒤에만 움직여요", color = C.sub, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("시작하기", enabled = ui.agreed, onClick = vm::finishLink)
    }
}

@Composable
private fun CheckCircle(on: Boolean) {
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(if (on) C.accent else Color.Transparent).border(1.5.dp, if (on) C.accent else C.sub, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Text("✓", color = C.onAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun CheckLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CheckCircle(true)
        Text(label, color = C.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = C.sub, fontSize = 13.sp)
    }
}

// ───────────────────────────── 연결 후: 아래 탭 5개 ─────────────────────────────

@Composable
fun MainScreen(ui: PhoneUi, vm: PhoneVm) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (ui.tab) {
                // 출발하면 홈이 P-8 주행 화면으로 바뀜
                Tab.HOME -> HomeTab(ui, vm)
                // 주행 중 탭 (P-8): 아기 상태 · [아기 상태 알리기] · 수유 · 휴게소
                Tab.DRIVE -> RoadTab(ui, vm)
                Tab.MEMBERS -> MembersTab(ui, vm)
                Tab.SIM -> SimTab(ui, vm)
                Tab.HISTORY -> HistoryTab(ui, vm)
                Tab.SETTINGS -> SettingsTab(ui, vm)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF14181D))
                .border(1.dp, C.line, RoundedCornerShape(28.dp))
                .padding(vertical = 6.dp, horizontal = 4.dp),
        ) {
            NavItem("홈", NavIcon.HOME, ui.tab == Tab.HOME, Modifier.weight(1f)) { vm.selectTab(Tab.HOME) }
            NavItem("구성원", NavIcon.PEOPLE, ui.tab == Tab.MEMBERS, Modifier.weight(1f)) { vm.selectTab(Tab.MEMBERS) }
            NavItem("3D 시뮬", NavIcon.CAR, ui.tab == Tab.SIM, Modifier.weight(1f)) { vm.selectTab(Tab.SIM) }
            // 주행 중 탭: 차가 주행 중이면 초록 점 (11/14 사용자: "주행중도 아래 탭에")
            NavItem("주행 중", NavIcon.DRIVE, ui.tab == Tab.DRIVE, Modifier.weight(1f), live = onTheRoad(ui.status)) { vm.selectTab(Tab.DRIVE) }
            // 히스토리는 설정 안에 (11/14 사용자: "히스토리를 설정에다가")
            NavItem("설정", NavIcon.GEAR, ui.tab == Tab.SETTINGS || ui.tab == Tab.HISTORY, Modifier.weight(1f)) { vm.selectTab(Tab.SETTINGS) }
        }
    }
}

private enum class NavIcon { HOME, PEOPLE, CAR, DRIVE, GEAR }

@Composable
private fun NavItem(text: String, icon: NavIcon, selected: Boolean, modifier: Modifier, live: Boolean = false, onClick: () -> Unit) {
    val color = if (selected) C.accent else C.sub
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val st = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            when (icon) {
                NavIcon.HOME -> drawPath(
                    Path().apply {
                        moveTo(w * 0.15f, w * 0.45f); lineTo(w * 0.5f, w * 0.15f); lineTo(w * 0.85f, w * 0.45f)
                        lineTo(w * 0.85f, w * 0.85f); lineTo(w * 0.15f, w * 0.85f); close()
                        moveTo(w * 0.42f, w * 0.85f); lineTo(w * 0.42f, w * 0.62f); lineTo(w * 0.58f, w * 0.62f); lineTo(w * 0.58f, w * 0.85f)
                    },
                    color, style = st,
                )
                NavIcon.PEOPLE -> {
                    // 어른 한 명 + 아이 한 명 (머리 · 어깨)
                    drawCircle(color, w * 0.13f, Offset(w * 0.38f, w * 0.3f), style = st)
                    drawArc(color, 180f, 180f, false, Offset(w * 0.14f, w * 0.52f), Size(w * 0.48f, w * 0.5f), style = st)
                    drawCircle(color, w * 0.1f, Offset(w * 0.72f, w * 0.44f), style = st)
                    drawArc(color, 180f, 180f, false, Offset(w * 0.56f, w * 0.62f), Size(w * 0.32f, w * 0.34f), style = st)
                }
                NavIcon.CAR -> {
                    // 위에서 본 차와 좌석 두 줄
                    drawRoundRect(color, Offset(w * 0.25f, w * 0.06f), Size(w * 0.5f, w * 0.88f), CornerRadius(w * 0.18f), style = st)
                    for (y in listOf(0.38f, 0.62f)) {
                        drawLine(color, Offset(w * 0.36f, w * y), Offset(w * 0.46f, w * y), st.width, StrokeCap.Round)
                        drawLine(color, Offset(w * 0.54f, w * y), Offset(w * 0.64f, w * y), st.width, StrokeCap.Round)
                    }
                }
                NavIcon.DRIVE -> {
                    // 핸들: 바깥 원 + 가운데 원 + 아래 · 양옆 살
                    drawCircle(color, w * 0.38f, style = st)
                    drawCircle(color, w * 0.1f, style = st)
                    drawLine(color, Offset(w * 0.12f, w * 0.5f), Offset(w * 0.4f, w * 0.5f), st.width, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.6f, w * 0.5f), Offset(w * 0.88f, w * 0.5f), st.width, StrokeCap.Round)
                    drawLine(color, Offset(w * 0.5f, w * 0.6f), Offset(w * 0.5f, w * 0.88f), st.width, StrokeCap.Round)
                    if (live) drawCircle(C.ok, w * 0.12f, Offset(w * 0.92f, w * 0.1f))
                }
                NavIcon.GEAR -> {
                    drawCircle(color, w * 0.16f, style = st)
                    val c = Offset(w / 2, w / 2)
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        val dx = kotlin.math.cos(a).toFloat()
                        val dy = kotlin.math.sin(a).toFloat()
                        drawLine(color, c + Offset(dx * w * 0.27f, dy * w * 0.27f), c + Offset(dx * w * 0.4f, dy * w * 0.4f), st.width, StrokeCap.Round)
                    }
                }
            }
        }
        Text(text, color = color, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ───────────────────────────── 3D 차량 시뮬 ─────────────────────────────

/** 지금은 위에서 본 좌석 배치만. 3D 좌석 움직임은 다음 단계 */
@Composable
private fun SimTab(ui: PhoneUi, vm: PhoneVm) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TitleThin("3D SIMULATION", 24.sp)
        ConnectionLine(ui)
        // AI 자세: 보호자가 고른 자리마다 등받이 · 앞뒤 (규칙 검사 뒤). 아래 3D 에 미리 보임
        PostureCard(ui, vm)
        val s = ui.status
        // 차가 SDK 로 읽은 좌석 상황 그대로 (좌석 수 · 움직일 수 있는 축 · 위치 · 착좌)
        val seats = s?.seats.orEmpty()
        // 트렁크: 차가 어림한 값 (없으면 휴대폰에서 짐만으로 어림). 짐은 차가 받은 여정, 없으면 보낸 여정
        val luggage = s?.trip ?: ui.sent
        val trunk = s?.trunk ?: TrunkState.guess(seats, luggage)
        // 좌석을 누르면 직접 맞추기 (앞뒤 · 등받이). 맞추는 동안 3D 에 바로 보임
        var sel by remember { mutableStateOf<String?>(null) }
        val foreEdit = remember { mutableStateMapOf<String, Int>() }
        val backEdit = remember { mutableStateMapOf<String, Int>() }
        // 차 정보가 아직 없으면 2열 4석 기본값으로 그리고 맞춰 볼 수 있게
        val shown = seats.ifEmpty { DEFAULT_SEATS }
        // 직접 맞춘 값 > AI 자세 미리보기 > 차가 알려 준 지금 위치
        val preview = ui.ai.guess
        val poses = shown.map { st ->
            val g = preview[st.id]
            st.copy(foreAft = foreEdit[st.id] ?: g?.foreAft ?: st.foreAft, backrest = backEdit[st.id] ?: g?.backrest ?: st.backrest).toPose(folded = st.id in trunk.folded)
        }
        val load = TrunkLoad(luggage?.strollers ?: 0, luggage?.suitcases ?: 0, luggage?.babyBags ?: 0, trunk.liters)
        Card {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("좌석을 누르면 직접 맞춰요 · 옆으로 끌어서 돌리기", color = C.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("▲ 차 앞", color = C.sub, fontSize = 11.sp)
            }
            if (preview.isNotEmpty()) {
                Text("AI 자세 미리보기 · [차에 적용] 전까지 차 좌석은 그대로예요", color = C.accent, fontSize = 12.sp)
            }
            Seat3DView(poses, Modifier.fillMaxWidth().height(320.dp), load, selected = sel, onSeatTap = { id -> sel = if (sel == id) null else id })
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(C.accent, "전동 좌석")
                Legend(C.warn, "앉아 있음 · 넘친 짐")
                Legend(Color(0xFF59636F), "수동 · 모름")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(LuggageColor.trunk, "트렁크")
                Legend(LuggageColor.stroller, "유모차")
                Legend(LuggageColor.suitcase, "캐리어")
                Legend(LuggageColor.bag, "아기 짐가방")
            }
        }
        LaunchedEffect(sel) {
            val id = sel ?: return@LaunchedEffect
            val g = ui.ai.guess[id] ?: return@LaunchedEffect
            if (id !in backEdit) backEdit[id] = g.backrest
            if (id !in foreEdit) g.foreAft?.let { foreEdit[id] = it }
        }
        shown.firstOrNull { it.id == sel }?.let { st ->
            SeatEditCard(
                st = st,
                who = vm.currentPlan()[st.id],
                memory = ui.seatMemory,
                foreAft = foreEdit[st.id] ?: st.foreAft ?: 50,
                backrest = backEdit[st.id] ?: st.backrest ?: 75,
                online = ui.carOnline,
                onFore = { foreEdit[st.id] = it },
                onBack = { backEdit[st.id] = it },
                onReset = { foreEdit.remove(st.id); backEdit.remove(st.id) },
                onSend = {
                    val f = foreEdit[st.id] ?: st.foreAft
                    val b = backEdit[st.id] ?: st.backrest
                    // 차가 움직일 수 있는 축만 보내고, 나머지는 기억만
                    vm.sendSeatAdjust(st.id, f.takeIf { st.canForeAft }, b.takeIf { st.canBackrest }, f, b)
                },
                onClose = { sel = null },
            )
        }
        TrunkCard(trunk, seats)
        if (seats.isEmpty()) {
            Text("차 앱이 좌석 정보를 보내면 실제 위치로 바뀌어요", color = C.sub, fontSize = 13.sp)
        }
    }
}

/** 차가 좌석 정보를 아직 안 보냈을 때 (2열 4석, 전동으로 가정) */
private val DEFAULT_SEATS = listOf(
    SeatState("R1_LEFT", 1, false, "운전석", null, null, true, true, null),
    SeatState("R1_RIGHT", 1, true, "조수석", null, null, true, true, null),
    SeatState("R2_LEFT", 2, false, "2열 왼쪽", null, null, true, true, null),
    SeatState("R2_RIGHT", 2, true, "2열 오른쪽", null, null, true, true, null),
)

/**
 * 3D 에서 누른 좌석 직접 맞추기: 앞뒤 위치 · 등받이 각도. 맞추는 값은 3D 에 바로 보이고,
 * [차로 보내기] 하면 차가 P단 · 카운트다운 · 착좌 · 문 확인 뒤 움직임. 지난번 이 자리 설정도 불러올 수 있음.
 */
@Composable
private fun SeatEditCard(
    st: SeatState,
    who: String?,
    memory: List<SeatRecord>,
    foreAft: Int,
    backrest: Int,
    online: Boolean,
    onFore: (Int) -> Unit,
    onBack: (Int) -> Unit,
    onReset: () -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    Card(highlight = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${st.label} 직접 맞추기", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (!who.isNullOrBlank()) Text("$who 자리", color = C.sub, fontSize = 12.sp)
            }
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(C.line).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Text("×", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
        val sliderColors = SliderDefaults.colors(thumbColor = C.accent, activeTrackColor = C.accent, inactiveTrackColor = C.line)
        run {
            Row(Modifier.fillMaxWidth()) {
                Text("앞뒤 위치" + if (st.canForeAft) "" else " · 차가 못 움직여요 (기록만)", color = C.sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("$foreAft%", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Slider(value = foreAft.toFloat(), onValueChange = { onFore(it.toInt()) }, valueRange = 0f..100f, colors = sliderColors)
            Row(Modifier.fillMaxWidth()) {
                Text("뒤", color = C.sub, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("앞", color = C.sub, fontSize = 11.sp)
            }
        }
        run {
            val deg = backrestDeg(backrest)
            Row(Modifier.fillMaxWidth()) {
                Text("등받이 각도" + if (st.canBackrest) "" else " · 차가 못 움직여요 (기록만)", color = C.sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("$deg°", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            // 왼쪽 = 세움(15°), 오른쪽 = 눕힘(50°). 보통 20~25°
            Slider(
                value = deg.toFloat(), onValueChange = { onBack(degToBackrest(it.toInt())) },
                valueRange = BACKREST_MIN_DEG.toFloat()..BACKREST_MAX_DEG.toFloat(), colors = sliderColors,
            )
            Row(Modifier.fillMaxWidth()) {
                Text("세움", color = C.sub, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("눕힘", color = C.sub, fontSize = 11.sp)
            }
        }
        // 이 자리에 이 사람이 앉았던 설정
        lastAt(memory, st.id, who?.takeIf { it.isNotBlank() })?.let { last ->
            val day = java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREAN).format(java.util.Date(last.at))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "지난번 ${last.who.ifBlank { "이 자리" }} · ${positionText(last.foreAft, last.backrest)} ($day)",
                    color = C.sub, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                SmallButton("지난번대로") {
                    last.foreAft?.let(onFore)
                    last.backrest?.let(onBack)
                }
            }
        }
        val movable = st.canForeAft || st.canBackrest
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("원래대로", Modifier.weight(1f), onClick = onReset)
            if (movable) {
                SmallButton("차로 보내기", Modifier.weight(1f), filled = online) { if (online) onSend() }
            } else {
                SmallButton("기억해 두기", Modifier.weight(1f), filled = true, onClick = onSend)
            }
        }
        Text(
            when {
                !movable -> "이 좌석은 차가 움직일 수 없어 설정만 기억해요 · 손으로 맞춰 주세요"
                online -> "차는 P단 · 5초 카운트다운 · 앉은 사람 · 문 확인 뒤에만 움직여요"
                else -> "차와 연결되면 보낼 수 있어요"
            },
            color = C.sub, fontSize = 11.sp,
        )
    }
}

/**
 * AI 자세 카드: 보호자가 2D 에서 고른 자리마다 AI(폰 안 Qwen3)가 등받이 · 앞뒤를 정하고 규칙이 검사한 결과.
 * 3D 에 미리 보이고, [차에 적용]을 누르면 좌석 하나씩 차로 보냄.
 */
@Composable
private fun PostureCard(ui: PhoneUi, vm: PhoneVm) {
    val plan = vm.currentPlan()
    if (plan.size <= 1 && ui.sent == null) {
        Card {
            Text("자리 배치가 아직 없어요", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("홈 > 여정 입력에서 자리마다 앉을 사람을 넣으면 AI가 자세를 맞춰요", color = C.sub, fontSize = 13.sp)
            SmallButton("여정 입력", filled = true) {
                vm.selectTab(Tab.HOME)
                vm.openEditor()
            }
        }
        return
    }
    val ai = ui.ai
    val busy = ai.stage in listOf(AiStage.DOWNLOADING, AiStage.LOADING, AiStage.THINKING)
    // 자리 배치가 바뀌면 자동으로 다시
    LaunchedEffect(plan) {
        vm.refreshAiInstalled()
        vm.autoPosture()
    }
    val s = ui.status
    Card(highlight = ai.stage == AiStage.DONE) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("AI 자세", color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                when {
                    busy -> "AI 계산 중…"
                    ai.stage == AiStage.DONE -> "다시 계산"
                    else -> ""
                },
                color = C.sub, fontSize = 11.sp,
                modifier = Modifier.clickable(enabled = !busy, onClick = vm::recommendPosture),
            )
        }
        when (ai.stage) {
            AiStage.DOWNLOADING -> Text("AI 모델 받는 중… ${ai.progress}%", color = C.text, fontSize = 15.sp)
            AiStage.LOADING, AiStage.THINKING -> Text("자리마다 등받이 · 앞뒤를 정하는 중이에요… 정한 뒤 안전 규칙으로 검사해요", color = C.text, fontSize = 15.sp)
            AiStage.IDLE -> Text("자세를 정하는 중이에요…", color = C.text, fontSize = 15.sp)
            AiStage.DONE -> {
                ai.note?.let { Text(it, color = C.sub, fontSize = 12.sp) }
                for ((seat, name) in plan.entries.sortedBy { it.key }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(seatName(seat), color = C.sub, fontSize = 14.sp, modifier = Modifier.width(92.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            val g = ai.guess[seat]
                            // 운전석은 차가 움직이지 않음: 운전할 수 없는 자세면 경고 (직접 맞춤)
                            val badDriver = seat == SeatLayout.DRIVER && g != null && g.why.startsWith("운전할 수 없는")
                            Text(
                                when {
                                    g != null -> g.text
                                    seat == SeatLayout.DRIVER -> "운전석은 직접 맞춰요"
                                    else -> "그대로"
                                },
                                color = if (badDriver) C.warn else C.sub, fontSize = 11.sp,
                                fontWeight = if (badDriver) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
                for (f in ai.fixes) Text("규칙이 고침 · $f", color = C.accent, fontSize = 12.sp)
            }
        }
        MemoryLines(ai.memory)
        // 차가 세운 계획 (3열 펼침 · 카시트 위치 등)
        if (ui.sent != null) {
            if (ui.carReceived && s?.planSummary != null) {
                Text("차 · ${s.planSummary}", color = C.sub, fontSize = 12.sp)
                for (n in s.planNotes) Text("· $n", color = C.sub, fontSize = 12.sp)
            } else {
                Text("차가 여정을 받는 중이에요…", color = C.sub, fontSize = 12.sp)
            }
        }
        val applying = ui.applying
        val running = s?.run?.let { !it.finished } == true
        PrimaryButton(
            if (applying != null) "차에 적용 중 · ${applying.first} / ${applying.second}" else "차에 적용",
            enabled = ai.stage == AiStage.DONE && ai.guess.isNotEmpty() && ui.carOnline && applying == null && !running && !ui.busy && s?.driving != true,
            onClick = vm::applyPosture,
        )
        Text(
            when {
                s?.driving == true -> "주행 중에는 좌석을 움직이지 않아요"
                !ui.carOnline -> "차와 연결되면 적용할 수 있어요"
                else -> "좌석 하나씩 차로 보내요 · 차는 P단 · 5초 카운트다운 · 앉은 사람 · 문 확인 뒤에만 움직여요"
            },
            color = C.sub, fontSize = 11.sp,
        )
        if (!ui.aiInstalled && !busy) {
            Text(
                "AI 모델이 없어 기억 · 규칙으로 정했어요 · AI 모델 받기 (343MB, 와이파이)",
                color = C.accent, fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = vm::downloadModel).padding(vertical = 2.dp),
            )
        }
    }
}

/** 추천에 참고한 기억 몇 줄 */
@Composable
private fun MemoryLines(lines: List<String>) {
    if (lines.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.line.copy(alpha = 0.25f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("참고한 기억", color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        for (l in lines.take(5)) Text(l, color = C.sub, fontSize = 12.sp)
    }
}

/** 트렁크 어림값: 용량 · 짐 · 막대. SDK 에 트렁크 API 가 없어 좌석 위치로 어림한 값임을 밝힘 */
@Composable
private fun TrunkCard(t: TrunkState, seats: List<SeatState>) {
    val over = t.used > t.liters
    Card(highlight = over) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("트렁크 · 어림값", color = C.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (t.rows >= 3) "3열 차" else "2열 차", color = C.sub, fontSize = 12.sp)
        }
        Text(
            if (t.used == 0) "약 ${t.liters}L · 실을 짐 없음" else "짐 ${t.used}L / 트렁크 약 ${t.liters}L",
            color = if (over) C.warn else C.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        )
        val ratio = if (t.liters <= 0) 0f else (t.used.toFloat() / t.liters).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.line)) {
            Box(Modifier.fillMaxWidth(ratio).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (over) C.warn else LuggageColor.trunk))
        }
        if (over) Text("${t.used - t.liters}L 넘쳐요 · 일부는 좌석 쪽에 실어야 해요", color = C.warn, fontSize = 13.sp)
        if (t.folded.isNotEmpty()) {
            val names = t.folded.map { id -> seats.firstOrNull { it.id == id }?.label ?: id }
            Text("짐 때문에 ${names.joinToString(", ")} 접기 (직접)", color = C.text, fontSize = 13.sp)
        }
        if (t.slide != 0) {
            Text(
                if (t.slide > 0) "뒷줄 좌석이 앞으로 가 있어 약 ${t.slide}L 넓어요" else "뒷줄 좌석이 뒤로 가 있어 약 ${-t.slide}L 좁아요",
                color = C.sub, fontSize = 13.sp,
            )
        }
        Text("${t.basis} · 차에 트렁크 센서가 없어 좌석 위치로 어림한 값이에요", color = C.sub, fontSize = 11.sp)
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(text, color = C.sub, fontSize = 11.sp)
    }
}

// ───────────────────────────── 히스토리 ─────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryTab(ui: PhoneUi, vm: PhoneVm) {
    // 누르면 펼쳐 보는 상세: 사람 이름 · 다녀온 여정 시각 · 보낸 여정 시각
    var openWho by remember { mutableStateOf<String?>(null) }
    var openRide by remember { mutableStateOf<Long?>(null) }
    var openSent by remember { mutableStateOf<Long?>(null) }
    val day = remember { java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREAN) }
    val full = remember { java.text.SimpleDateFormat("M월 d일 (E) HH:mm", java.util.Locale.KOREAN) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF1C2228)).clickable { vm.selectTab(Tab.SETTINGS) },
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = C.text, fontSize = 22.sp) }
            TitleThin("HISTORY", 26.sp)
        }
        Text("설정 › 히스토리 · 카드를 누르면 자세히 보여요", color = C.sub, fontSize = 12.sp)

        // 1) 사람별 좌석 기억: 누가 어느 자리에 몇 번, 어떤 자세로 앉았는지 → AI 자세가 평소 자세부터 씀
        Text("사람별 좌석 기억 · AI 자세에 써요", color = C.sub, fontSize = 13.sp)
        val people = personHabits(ui.seatMemory)
        if (people.isEmpty()) {
            Card {
                Text("아직 기억한 자리가 없어요", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("내리기 전 · 좌석을 직접 맞췄을 때 남아요", color = C.sub, fontSize = 13.sp)
            }
        } else {
            for (p in people) {
                val open = openWho == p.who
                Card(Modifier.clickable { openWho = if (open) null else p.who }, highlight = open) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.who, color = C.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (p.detail.isNotBlank()) Text(p.detail, color = C.sub, fontSize = 12.sp)
                        Text(if (open) "  ▲" else "  ▼", color = C.sub, fontSize = 12.sp)
                    }
                    p.uses.take(if (open) p.uses.size else 2).forEachIndexed { i, use ->
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    seatName(use.seat) + if (i == 0) " · 평소 자리" else "",
                                    color = if (i == 0) C.accent else C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                                )
                                Text("${use.times}번", color = C.sub, fontSize = 12.sp)
                            }
                            Text(
                                listOf(positionText(use.last.foreAft, use.last.backrest), "마지막 ${day.format(java.util.Date(use.last.at))} · ${use.last.source}")
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                color = C.sub, fontSize = 12.sp,
                            )
                        }
                    }
                    if (open) {
                        // 이 사람의 기록 전부 (새 것이 위)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
                        Text("기록 전체", color = C.sub, fontSize = 12.sp)
                        for (rec in ui.seatMemory.filter { it.who == p.who }.sortedByDescending { it.at }.take(15)) {
                            Text(
                                listOf(full.format(java.util.Date(rec.at)), seatName(rec.seat), positionText(rec.foreAft, rec.backrest), rec.source)
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                color = C.text, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
            Text(
                "좌석 기억 지우기", color = C.sub, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = vm::clearSeatMemory).padding(8.dp),
            )
        }

        // 2) 다녀온 여정: 내리기 직전 마지막 상태
        Text("다녀온 여정 · 내리기 전 상태", color = C.sub, fontSize = 13.sp)
        if (ui.rides.isEmpty()) {
            Card {
                Text("아직 다녀온 여정이 없어요", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("도착해서 P단에 두면 남아요", color = C.sub, fontSize = 13.sp)
            }
        } else {
            for (r in ui.rides.take(20)) {
                val open = openRide == r.at
                Card(Modifier.clickable { openRide = if (open) null else r.at }, highlight = open) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOfNotNull(
                                full.format(java.util.Date(r.at)),
                                r.km?.let { "%.0fkm".format(it) },
                                r.minutes?.let { "${it}분" },
                            ).joinToString(" · "),
                            color = C.sub, fontSize = 12.sp, modifier = Modifier.weight(1f),
                        )
                        Text(if (open) "▲" else "▼", color = C.sub, fontSize = 12.sp)
                    }
                    Text(r.destination.ifBlank { "목적지 모름" }, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (!open) {
                        Text(
                            r.seats.entries.filter { it.value != "운전자" }.sortedBy { it.key }.joinToString("  ") { (k, v) -> "${seatName(k)} $v" }.ifBlank { "운전자만" },
                            color = C.text, fontSize = 13.sp,
                        )
                    } else {
                        // 자리마다 누가 · 내리기 직전 자세
                        for ((seat, who) in r.seats.entries.sortedBy { it.key }) {
                            val pos = r.positions[seat]
                            Row(Modifier.fillMaxWidth()) {
                                Text(seatName(seat), color = C.sub, fontSize = 13.sp, modifier = Modifier.width(88.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(who, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    pos?.let { (f, b) -> positionText(f, b).takeIf { it.isNotBlank() } }?.let { Text(it, color = C.sub, fontSize = 12.sp) }
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
                        Text("탄 사람 · 짐: " + tripChips(r.trip).joinToString(" · ") { it.first }.ifBlank { "운전자만" }, color = C.text, fontSize = 13.sp)
                        Text(if (r.luggageLiters > 0) "짐 부피 약 ${r.luggageLiters}L" else "짐 없음", color = C.sub, fontSize = 12.sp)
                        Text("비슷한 여정을 다시 갈 때 이 자세를 참고해요", color = C.sub, fontSize = 11.sp)
                    }
                }
            }
            Text(
                "다녀온 여정 지우기", color = C.sub, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = vm::clearRides).padding(8.dp),
            )
        }

        // 3) 차로 보낸 여정
        Text("차로 보낸 여정", color = C.sub, fontSize = 13.sp)
        if (ui.history.isEmpty()) {
            Card { Text("아직 차로 보낸 여정이 없어요", color = C.text, fontSize = 15.sp) }
        } else {
            for (h in ui.history.take(20)) {
                val open = openSent == h.at
                Card(Modifier.clickable { openSent = if (open) null else h.at }, highlight = open) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(full.format(java.util.Date(h.at)), color = C.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(if (open) "▲" else "▼", color = C.sub, fontSize = 12.sp)
                    }
                    val chips = tripChips(h.trip)
                    Text(chips.filter { it.second }.joinToString(" · ") { it.first }.ifBlank { "운전자만" }, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (open) {
                        val bags = chips.filter { !it.second }.joinToString(" · ") { it.first }
                        Text(if (bags.isBlank()) "짐 없음" else "짐: $bags", color = C.sub, fontSize = 12.sp)
                        if (h.trip.destination.isNotBlank()) Text("목적지 ${h.trip.destination}", color = C.sub, fontSize = 12.sp)
                    }
                }
            }
            Text(
                "보낸 여정 지우기", color = C.sub, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = vm::clearHistory).padding(8.dp),
            )
        }
    }
}

// ───────────────────────────── 설정 ─────────────────────────────

@Composable
private fun SettingsTab(ui: PhoneUi, vm: PhoneVm) {
    var doc by remember { mutableStateOf<Pair<String, String>?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TitleThin("SETTINGS", 26.sp)
        Card {
            Text("등록한 차", color = C.sub, fontSize = 12.sp)
            Text(ui.code?.let { "연결 코드 ${it.take(3)} ${it.drop(3)}" } ?: "없음", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            ConnectionLine(ui)
            SmallButton("차 등록 해제", onClick = vm::disconnect)
        }
        // 히스토리: 사람별 좌석 기억 · 다녀온 여정 · 차로 보낸 여정
        Card(Modifier.clickable { vm.selectTab(Tab.HISTORY) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("히스토리", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "좌석 기억 ${personHabits(ui.seatMemory).size}명 · 다녀온 여정 ${ui.rides.size} · 보낸 여정 ${ui.history.size}",
                        color = C.sub, fontSize = 12.sp,
                    )
                }
                Text("›", color = C.sub, fontSize = 22.sp)
            }
        }
        Card {
            Text("약관", color = C.text, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { doc = "약관" to TERMS })
            Text("개인정보 처리방침", color = C.text, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { doc = "개인정보 처리방침" to PRIVACY })
            Text("버전 ${BuildConfig.VERSION_NAME}", color = C.sub, fontSize = 12.sp)
        }
    }
    doc?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { doc = null },
            confirmButton = { TextButton(onClick = { doc = null }) { Text("확인", color = C.accent) } },
            title = { Text(title, color = C.text) },
            text = { Text(body, color = C.sub, fontSize = 14.sp) },
            containerColor = C.card,
        )
    }
}

@Composable
private fun ConnectionLine(ui: PhoneUi) {
    val (color, text) = when {
        !ui.relayReachable -> C.danger to "중계 서버에 닿지 않아요 · USB 연결 확인"
        !ui.carOnline -> C.warn to "차 앱이 꺼져 있어요"
        else -> C.ok to "차 연결됨"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, color = C.sub, fontSize = 12.sp)
    }
}

// ───────────────────────────── P-4 홈 ─────────────────────────────

/** 여정을 칩으로: 사람은 실선, 짐은 흐린 색 */
private fun tripChips(d: TripDraft): List<Pair<String, Boolean>> = listOfNotNull(
    d.seniors.takeIf { it > 0 }?.let { "어르신 $it" to true },
    d.adults.takeIf { it > 0 }?.let { "어른 $it" to true },
    d.children.takeIf { it > 0 }?.let { "아이 $it" to true },
    d.infants.takeIf { it > 0 }?.let { "아기 $it · 카시트" to true },
    d.strollers.takeIf { it > 0 }?.let { "유모차${if (it > 1) " $it" else ""}" to false },
    d.suitcases.takeIf { it > 0 }?.let { "캐리어${if (it > 1) " $it" else ""}" to false },
    d.babyBags.takeIf { it > 0 }?.let { "아기 짐가방${if (it > 1) " $it" else ""}" to false },
)

/** P-4 홈: 다음 여정 · 출발 계획 추천 · 출발 준비 상태 · [출발 준비] */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeTab(ui: PhoneUi, vm: PhoneVm) {
    val s = ui.status
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TitleThin("WELCOME") }
            val chip = when {
                s?.arrived == true -> "도착"
                s?.driving == true -> "주행 중"
                else -> "출발 전"
            }
            Text(
                chip, color = C.text, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C2228)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        ConnectionLine(ui)

        // 다음 여정
        val trip = ui.sent
        Card {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("다음 여정", color = C.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (trip != null) {
                    Text("고치기 ›", color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = vm::openEditor))
                }
            }
            if (trip == null) {
                Text("누가 타고 무엇을 싣나요?", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                if (ui.layout.isEmpty) {
                    Text("자리마다 앉을 사람과 트렁크 짐을 넣으면 차가 자리를 준비해요", color = C.sub, fontSize = 13.sp)
                } else {
                    // 아직 안 보낸 배치 (아기는 미리 카시트 자리에 들어가 있음)
                    Text(
                        ui.layout.seats.entries.sortedBy { it.key }.joinToString("  ") { (k, p) -> "${seatName(k)} ${p.label}" } +
                            if (ui.layout.trunk.isNotEmpty()) "  · 짐 ${ui.layout.trunk.size}개" else "",
                        color = C.text, fontSize = 14.sp,
                    )
                    Text("아직 차로 보내지 않았어요 · 눌러서 자리를 더 넣어요", color = C.sub, fontSize = 12.sp)
                }
                SmallButton("여정 입력", filled = true, onClick = vm::openEditor)
            } else {
                if (trip.destination.isNotBlank()) Text(trip.destination, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 지금 고치는 여정이 홈 카드와 같으면 구성원 이름으로 보여 줌 ("이봄 (아기)")
                    val chips = if (sameTrip(trip, ui.draft)) {
                        draftChips(trip, ui.picked, ui.members, ui.luggage).map { it.label to it.person }
                    } else {
                        tripChips(trip)
                    }
                    for ((label, person) in chips) {
                        Text(
                            label, color = if (person) C.text else C.sub, fontSize = 13.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (person) Color(0xFF1C2228) else Color.Transparent)
                                .border(1.dp, C.line, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // 차가 세운 출발 계획 추천
        if (ui.carReceived && s?.planSummary != null) {
            Card(highlight = true) {
                Text("출발 계획 추천", color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(s.planSummary, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                for (n in s.planNotes.take(2)) Text("· $n", color = C.sub, fontSize = 13.sp)
            }
        }

        // 출발 준비 상태
        ReadinessCard(ui)
        val run = s?.run
        val running = run != null && !run.finished
        PrimaryButton(
            when {
                running -> "좌석 맞추는 중…"
                run?.finished == true && ui.prepareSent -> "다시 맞추기"
                else -> "출발 준비"
            },
            enabled = ui.carReceived && ui.carOnline && !running && !ui.busy && s?.driving != true,
            onClick = vm::prepare,
        )
    }
}

/** 2D 배치에서 트렁크를 고른 상태 */
private const val TRUNK = "TRUNK"

/**
 * 여정 입력 (홈의 [여정 입력] · [고치기]): 위에서 본 차 2D. 자리를 누르면 앉을 사람(구성원 · 종류)을,
 * 트렁크를 누르면 짐(구성원 짐 · 글로 적기)을 넣음. [차로 보내기] 뒤 3D 시뮬에서 AI 가 자세를 맞춤.
 */
@Composable
fun TripEditScreen(ui: PhoneUi, vm: PhoneVm) {
    // 자리를 고르는 동안 AI 모델을 미리 띄워 둠 (3D 시뮬에서 바로 자세 계산)
    LaunchedEffect(Unit) { vm.warmUpAi() }
    var sel by remember { mutableStateOf<String?>(null) }
    val seats = ui.status?.seats?.ifEmpty { null } ?: DEFAULT_SEATS
    val trunkLiters = ui.status?.trunk?.liters ?: TrunkState.guess(seats, null).liters
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF1C2228)).clickable(onClick = vm::closeEditor),
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = C.text, fontSize = 24.sp) }
            Text("다음 여정", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (!ui.layout.isEmpty) {
                Text("처음부터", color = C.sub, fontSize = 13.sp, modifier = Modifier.clickable {
                    sel = null
                    vm.clearLayout()
                }.padding(8.dp))
            }
        }
        Text("자리를 누르고 앉을 사람을 넣어요 · 트렁크를 누르면 짐을 넣어요", color = C.sub, fontSize = 13.sp)

        // 지난번처럼 앉기: 다녀온 여정 · 보낸 여정의 배치를 그대로 채움
        val past = remember(ui.rides, ui.history) { vm.pastTrips() }
        if (past.isNotEmpty()) {
            Card {
                Text("지난번처럼 앉기", color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                for (p in past) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            sel = null
                            vm.sitLikeBefore(p)
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(p.line, color = C.sub, fontSize = 12.sp, maxLines = 2)
                        }
                        Text("그대로 ›", color = C.accent, fontSize = 13.sp)
                    }
                }
            }
        }

        Card { LayoutMap(ui.layout, seats, sel, trunkLiters) { id -> sel = if (sel == id) null else id } }

        when (val id = sel) {
            null -> Unit
            TRUNK -> Card(highlight = true) { TrunkPicker(ui, vm) { sel = null } }
            else -> seats.firstOrNull { it.id == id }?.let { st -> Card(highlight = true) { SeatPicker(ui, vm, st) { sel = null } } }
        }

        for (w in LayoutRules.warnings(ui.layout, trunkLiters)) Text("! $w", color = C.warn, fontSize = 12.sp)

        val driving = ui.status?.driving == true
        PrimaryButton(
            when {
                ui.busy && ui.ai.stage == AiStage.THINKING -> "AI가 자세를 정하는 중…"
                ui.busy -> "차로 보내는 중…"
                ui.sent == null -> "차로 보내기"
                ui.draftChanged -> "고친 내용 다시 보내기"
                else -> "다시 보내기"
            },
            enabled = ui.carOnline && !ui.busy && !driving,
            onClick = vm::sendTrip,
        )
        Text(
            when {
                !ui.carOnline -> "차와 연결되면 보낼 수 있어요"
                driving -> "주행 중에는 좌석을 움직이지 않아요 · 정차(P)한 뒤 보내 주세요"
                else -> "누르면 AI가 자리마다 등받이 · 앞뒤를 정하고, 차가 5초 뒤 좌석을 맞춰요 · 차 화면에서 취소할 수 있어요"
            },
            color = C.sub, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 위에서 본 차: 줄마다 왼쪽 · 오른쪽 자리, 맨 뒤 트렁크 */
@Composable
private fun LayoutMap(l: SeatLayout, seats: List<SeatState>, sel: String?, trunkLiters: Int, onTap: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("▲ 차 앞", color = C.sub, fontSize = 11.sp)
        val body = RoundedCornerShape(topStart = 48.dp, topEnd = 48.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
        Column(
            Modifier.fillMaxWidth(0.86f).clip(body).border(1.5.dp, C.line, body).padding(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (row in seats.map { it.row }.distinct().sorted()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (right in listOf(false, true)) {
                        val st = seats.firstOrNull { it.row == row && it.right == right }
                        if (st == null) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            SeatTile(st, l.seats[st.id], st.id == SeatLayout.DRIVER, sel == st.id, Modifier.weight(1f)) { onTap(st.id) }
                        }
                    }
                }
            }
            TrunkTile(l, trunkLiters, sel == TRUNK) { onTap(TRUNK) }
        }
    }
}

@Composable
private fun SeatTile(st: SeatState, p: Placed?, driver: Boolean, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val filled = p != null || driver
    Column(
        modifier
            .height(84.dp)
            .clip(shape)
            .background(if (selected) C.cardHi else if (filled) Color(0xFF1C2228) else Color.Transparent)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) C.accent else C.line, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Text(st.label, color = C.sub, fontSize = 11.sp, maxLines = 1)
        Text(p?.label ?: if (driver) "운전자" else "＋", color = if (filled) C.text else C.sub, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        p?.need?.takeIf { it != SeatNeed.BELT }?.let { Text(it.label, color = C.accent, fontSize = 10.sp, maxLines = 1) }
    }
}

@Composable
private fun TrunkTile(l: SeatLayout, trunkLiters: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val need = (l.luggageLiters / 0.75).toInt()
    val over = trunkLiters > 0 && need > trunkLiters
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) C.cardHi else LuggageColor.trunk.copy(alpha = 0.12f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) C.accent else if (over) C.warn else C.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("트렁크", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                if (l.trunk.isEmpty()) "약 ${trunkLiters}L" else "${l.trunk.size}개 · 약 ${l.luggageLiters}L / ${trunkLiters}L",
                color = if (over) C.warn else C.sub, fontSize = 12.sp,
            )
        }
        Text(
            if (l.trunk.isEmpty()) "눌러서 짐 넣기" else l.trunk.joinToString(" · ") { it.label },
            color = C.sub, fontSize = 12.sp, maxLines = 2,
        )
    }
}

/** 자리 하나에 넣을 사람: 구성원(이름) 또는 종류. 안전 규칙에 걸리는 것은 흐리게, 누르면 이유를 알림 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeatPicker(ui: PhoneUi, vm: PhoneVm, st: SeatState, onClose: () -> Unit) {
    val now = ui.layout.seats[st.id]
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${st.label}에 누가 앉나요?", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(C.line).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("×", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
    if (now != null) Text("지금 · ${now.label} (${now.detail})", color = C.sub, fontSize = 12.sp)
    if (ui.members.isNotEmpty()) {
        Text("구성원", color = C.sub, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in ui.members) {
                val p = Placed.of(m)
                val at = ui.layout.seats.entries.firstOrNull { it.value.memberId == m.id }?.key
                val where = at?.takeIf { it != st.id }?.let { " (${seatName(it)})" }.orEmpty()
                PickChip("${m.name} · ${m.need?.label ?: m.who.label}$where", on = at == st.id, person = LayoutRules.blocked(st.id, p) == null) {
                    if (vm.placeSeat(st.id, p)) onClose()
                }
            }
        }
    } else {
        Text(
            "구성원 탭에 가족을 적어 두면 이름으로 넣을 수 있어요 ›",
            color = C.accent, fontSize = 12.sp,
            modifier = Modifier.clickable {
                vm.closeEditor()
                vm.selectTab(Tab.MEMBERS)
            },
        )
    }
    Text("종류로 넣기", color = C.sub, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in GenericRider.entries) {
            val p = Placed(g.label, g.who, g.need)
            PickChip(g.chip, on = false, person = LayoutRules.blocked(st.id, p) == null) {
                if (vm.placeSeat(st.id, p)) onClose()
            }
        }
    }
    when {
        st.id == SeatLayout.DRIVER -> Text("운전석을 비워 두면 '운전자'로 셈해요", color = C.sub, fontSize = 11.sp)
        st.row == 1 -> Text("카시트 · 부스터 아이는 앞좌석에 앉을 수 없어요 (에어백)", color = C.sub, fontSize = 11.sp)
    }
    if (now != null) {
        SmallButton("비우기", Modifier.fillMaxWidth()) {
            vm.clearSeat(st.id)
            onClose()
        }
    }
}

/** 트렁크에 넣을 짐: 구성원 탭의 짐(누르면 넣고 다시 누르면 뺌) 또는 글로 적기 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrunkPicker(ui: PhoneUi, vm: PhoneVm, onClose: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("트렁크에 무엇을 싣나요?", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(C.line).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("×", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
    if (ui.layout.trunk.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in ui.layout.trunk) RemovableChip("${t.label} · ${t.liters}L") { vm.removeTrunk(t.id) }
        }
    }
    if (ui.luggage.isNotEmpty()) {
        Text("구성원 짐", color = C.sub, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in ui.luggage) {
                PickChip("${l.name} · ${l.estLiters}L", ui.layout.trunk.any { it.luggageId == l.id }, person = false) { vm.toggleTrunkLuggage(l) }
            }
        }
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormField("글로 넣기", typed, { typed = it.take(20) }, "예: 24인치 캐리어, 디럭스 유모차", Modifier.weight(1f))
        SmallButton("넣기", filled = typed.isNotBlank()) {
            if (vm.addTrunkText(typed)) typed = ""
        }
    }
    val preview = typed.takeIf { it.isNotBlank() }?.let { TrunkPut.typed(it, 0) }
    Text(
        preview?.let { "→ ${it.bag.label} · 약 ${it.liters}L" } ?: "크기를 적으면 더 정확해요 (24인치 · 60L · 55x40x20)",
        color = if (preview != null) C.accent else C.sub, fontSize = 12.sp,
    )
}

/** × 로 빼는 칩 */
@Composable
private fun RemovableChip(text: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1C2228))
            .border(1.dp, C.line, RoundedCornerShape(18.dp))
            .clickable(onClick = onRemove)
            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, color = C.text, fontSize = 13.sp)
        Box(Modifier.size(22.dp).clip(CircleShape).background(C.line), contentAlignment = Alignment.Center) {
            Text("×", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PickChip(text: String, on: Boolean, person: Boolean, onClick: () -> Unit) {
    Text(
        (if (on) "✓ " else "+ ") + text,
        color = if (on) C.onAccent else if (person) C.text else C.sub,
        fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) C.accent else if (person) Color(0xFF1C2228) else Color.Transparent)
            .border(1.dp, if (on) C.accent else C.line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

// ───────────────────────────── 구성원 ─────────────────────────────

/**
 * 구성원 탭: [사람 추가] · [짐 추가]를 누르고 한 줄로 적음.
 * 아이는 나이 · 키로 카시트 단계를, 짐은 크기 · 무게로 부피를 어림. 몰라서 안 적어도 됨.
 */
@Composable
private fun MembersTab(ui: PhoneUi, vm: PhoneVm) {
    // 지금 열린 입력: "person" · "luggage" · null
    var adding by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TitleThin("MEMBERS", 26.sp)
        Text("자주 타는 가족과 짐을 적어 두면 여정 입력에서 한 번에 넣고, 이름으로 말해도 알아들어요", color = C.sub, fontSize = 13.sp)

        Card {
            Text("사람 · 운전자는 빼고", color = C.sub, fontSize = 12.sp)
            if (ui.members.isEmpty()) Text("아직 없어요", color = C.text, fontSize = 15.sp)
            for (m in ui.members) PresetRow(m.name, m.detail, m.note) { vm.removeMember(m.id) }
            if (adding == "person") {
                PersonForm(vm, onClose = { adding = null })
            } else {
                SmallButton("+ 사람 추가", Modifier.fillMaxWidth()) { adding = "person" }
            }
        }

        Card {
            Text("자주 싣는 짐", color = C.sub, fontSize = 12.sp)
            if (ui.luggage.isEmpty()) Text("아직 없어요", color = C.text, fontSize = 15.sp)
            for (l in ui.luggage) PresetRow(l.name, l.detail, l.note) { vm.removeLuggage(l.id) }
            if (adding == "luggage") {
                LuggageForm(vm, onClose = { adding = null })
            } else {
                SmallButton("+ 짐 추가", Modifier.fillMaxWidth()) { adding = "luggage" }
            }
        }
    }
}

@Composable
private fun PresetRow(name: String, detail: String, note: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(name, color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = C.sub, fontSize = 12.sp)
            if (note.isNotBlank()) Text(note, color = C.sub, fontSize = 12.sp)
        }
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(C.line).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Text("×", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
    }
}

/** [사람 추가] 칸: 이름 · 나이 · 키 · 몸무게 · 기타 사항. 이름 말고는 비워도 됨 */
@Composable
private fun PersonForm(vm: PhoneVm, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    // 카시트: null 모름 · true 있음 · false 없음
    var hasSeat by remember { mutableStateOf<Boolean?>(null) }
    var dir by remember { mutableStateOf<CarSeatDir?>(null) }
    var angle by remember { mutableStateOf("") }
    FormField("이름 (호칭)", name, { name = it.take(12) }, "예: 지민, 어머니, 둘째")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormField("나이", age, { age = it.take(8) }, "5살 · 18개월", Modifier.weight(1f))
        FormField("키 (cm)", height, { height = it.filter(Char::isDigit).take(3) }, "110", Modifier.weight(1f), number = true)
        FormField("몸무게 (kg)", weight, { weight = it.filter(Char::isDigit).take(3) }, "18", Modifier.weight(1f), number = true)
    }
    FormField("기타 사항", note, { note = it.take(40) }, "예: 차멀미, 허리 불편, 창가 좋아함")
    val base = PresetParser.personFrom(name, age, height, weight, note)
    // 아기 · 아이면 카시트를 물어봄 (있는지 · 방향 · 각도까지만)
    val kid = base != null && (base.who == Who.BABY || base.who == Who.CHILD) && base.suggested != SeatNeed.BELT
    if (kid) {
        Text("카시트", color = C.sub, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectChip("있어요", hasSeat == true) {
                hasSeat = if (hasSeat == true) null else true
                if (dir == null) dir = when (base!!.suggested) {
                    SeatNeed.REAR_SEAT -> CarSeatDir.REAR
                    SeatNeed.BOOSTER -> CarSeatDir.BOOSTER
                    else -> CarSeatDir.FRONT
                }
            }
            SelectChip("없어요", hasSeat == false) { hasSeat = if (hasSeat == false) null else false }
        }
        if (hasSeat == true) {
            Text("다는 방향", color = C.sub, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in CarSeatDir.entries) SelectChip(d.label, dir == d) { dir = d }
            }
            if (dir != CarSeatDir.BOOSTER) {
                FormField("기울기 (°)", angle, { angle = it.filter(Char::isDigit).take(2) }, if (dir == CarSeatDir.REAR) "예: 40" else "예: 20", Modifier.width(120.dp), number = true)
            }
        }
    }
    val m = base?.let {
        if (!kid) it else it.copy(
            hasCarSeat = hasSeat,
            carSeatDir = dir.takeIf { hasSeat == true },
            carSeatAngle = angle.toIntOrNull()?.takeIf { hasSeat == true && dir != CarSeatDir.BOOSTER && it in 0..60 },
        )
    }
    Text(
        m?.let { "→ ${it.detail}" } ?: "이름 말고는 몰라도 돼요. 나이 · 키 · 몸무게를 적으면 카시트 단계를 맞춰 골라요",
        color = if (m != null) C.accent else C.sub, fontSize = 12.sp,
    )
    m?.carSeatWarnings?.forEach { Text("! $it", color = C.warn, fontSize = 12.sp) }
    FormButtons(enabled = m != null, onClose = onClose) {
        if (m != null && vm.addMember(m)) {
            name = ""; age = ""; height = ""; weight = ""; note = ""
            hasSeat = null; dir = null; angle = ""
        }
    }
}

/**
 * 처음 시작할 때 아기 설정: 애칭 · 나이 · 키 · 몸무게 · 카시트(있는지 · 방향 · 기울기) · 수유 간격.
 * 애칭만 있으면 저장되고, 나머지는 몰라도 됨. [나중에 할게요] 로 건너뜀. 다른 사람은 구성원 탭에서 추가.
 */
@Composable
fun BabySetupScreen(vm: PhoneVm) {
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var hasSeat by remember { mutableStateOf<Boolean?>(null) }
    var dir by remember { mutableStateOf<CarSeatDir?>(null) }
    var angle by remember { mutableStateOf("") }
    var feed by remember { mutableStateOf<Int?>(180) }
    Column(
        Modifier.fillMaxSize().background(C.bg).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TitleThin("OUR BABY", 26.sp)
        Text("아기 정보부터 알려 주세요", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("카시트 자리 추천과 수유 알림에 써요. 애칭 말고는 몰라도 돼요", color = C.sub, fontSize = 13.sp)
        FormField("애칭", name, { name = it.take(12) }, "예: 이봄")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormField("나이", age, { age = it.take(8) }, "10개월", Modifier.weight(1f))
            FormField("키 (cm)", height, { height = it.filter(Char::isDigit).take(3) }, "72", Modifier.weight(1f), number = true)
            FormField("몸무게 (kg)", weight, { weight = it.filter(Char::isDigit).take(2) }, "9", Modifier.weight(1f), number = true)
        }
        // 나이를 안 적으면 아기로 봄
        val base = PresetParser.personFrom(name, age, height, weight, "")?.let {
            if (age.isBlank() || it.who == Who.ADULT || it.who == Who.SENIOR) it.copy(who = Who.BABY) else it
        }
        Text("카시트", color = C.sub, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectChip("있어요", hasSeat == true) {
                hasSeat = if (hasSeat == true) null else true
                if (dir == null) dir = when (base?.suggested) {
                    SeatNeed.BOOSTER -> CarSeatDir.BOOSTER
                    SeatNeed.CAR_SEAT -> CarSeatDir.FRONT
                    else -> CarSeatDir.REAR
                }
            }
            SelectChip("없어요", hasSeat == false) { hasSeat = if (hasSeat == false) null else false }
        }
        if (hasSeat == true) {
            Text("다는 방향", color = C.sub, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (d in CarSeatDir.entries) SelectChip(d.label, dir == d) { dir = d }
            }
            if (dir != CarSeatDir.BOOSTER) {
                FormField("기울기 (°)", angle, { angle = it.filter(Char::isDigit).take(2) }, if (dir == CarSeatDir.REAR) "예: 40" else "예: 20", Modifier.width(120.dp), number = true)
            }
        }
        Text("수유 간격 · 위아래로 돌려 골라요", color = C.sub, fontSize = 12.sp)
        // 휠: 안 해요 · 모름, 1시간 ~ 6시간 (30분 단위)
        val feedOptions = remember { listOf<Int?>(null) + (60..360 step 30).toList() }
        WheelPicker(
            options = feedOptions.map { it?.let(::feedText) ?: "안 해요 · 모름" },
            selected = feedOptions.indexOf(feed).coerceAtLeast(0),
            onSelected = { feed = feedOptions[it] },
            modifier = Modifier.fillMaxWidth(),
        )
        val m = base?.copy(
            hasCarSeat = hasSeat,
            carSeatDir = dir.takeIf { hasSeat == true },
            carSeatAngle = angle.toIntOrNull()?.takeIf { hasSeat == true && dir != CarSeatDir.BOOSTER && it in 0..60 },
            feedMin = feed,
        )
        m?.let { Text("→ ${it.detail}", color = C.accent, fontSize = 12.sp) }
        m?.carSeatWarnings?.forEach { Text("! $it", color = C.warn, fontSize = 12.sp) }
        Spacer(Modifier.height(4.dp))
        PrimaryButton(if (m != null) "저장하고 시작" else "애칭을 적어 주세요", enabled = m != null) { vm.saveBaby(m) }
        Text(
            "나중에 할게요", color = C.sub, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { vm.saveBaby(null) }.padding(8.dp),
        )
        Text("다른 가족 · 짐은 나중에 구성원 탭에서 더할 수 있어요", color = C.sub, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** [짐 추가] 칸: 이름 · 크기 · 무게 · 기타 사항. 이름 말고는 비워도 됨 */
@Composable
private fun LuggageForm(vm: PhoneVm, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    FormField("이름", name, { name = it.take(20) }, "예: 큰 캐리어, 디럭스 유모차, 기저귀 가방")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FormField("크기", size, { size = it.take(16) }, "24인치 · 55x40x20 · 60L", Modifier.weight(2f))
        FormField("무게 (kg)", weight, { weight = it.filter(Char::isDigit).take(2) }, "18", Modifier.weight(1f), number = true)
    }
    FormField("기타 사항", note, { note = it.take(40) }, "예: 깨지기 쉬움, 먼저 꺼냄")
    val l = PresetParser.luggageFrom(name, size, weight, note)
    Text(
        l?.let { "→ ${it.detail}" } ?: "이름 말고는 몰라도 돼요. 크기를 모르면 종류별 기본 크기로 셈해요",
        color = if (l != null) C.accent else C.sub, fontSize = 12.sp,
    )
    FormButtons(enabled = l != null, onClose = onClose) {
        if (vm.addLuggage(name, size, weight, note)) {
            name = ""; size = ""; weight = ""; note = ""
        }
    }
}

/**
 * 위아래로 돌려 고르는 휠 (가운데 줄이 고른 값). 세 줄이 보이고, 놓으면 가장 가까운 줄에 멈춤.
 * 줄을 눌러도 그 값으로 돌아감.
 */
@Composable
private fun WheelPicker(options: List<String>, selected: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    val rowH = 44.dp
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val fling = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(lazyListState = state)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { rowH.toPx() }
    // 가운데 줄: 위 여백이 한 줄이라 맨 위에 보이는 줄이 가운데 (반 넘게 넘어갔으면 다음 줄)
    val center by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf {
            (state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0).coerceIn(0, options.lastIndex)
        }
    }
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress && center != selected) onSelected(center)
    }
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.height(rowH * 3).clip(shape).background(Color(0xFF141922)).border(1.dp, C.line, shape)) {
        // 가운데 줄 표시
        Box(
            Modifier.fillMaxWidth().height(rowH).align(Alignment.Center)
                .background(C.accent.copy(alpha = 0.12f))
                .border(1.dp, C.accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
        )
        androidx.compose.foundation.lazy.LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = rowH),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(options.size) { i ->
                val on = i == center
                Box(
                    Modifier.fillMaxWidth().height(rowH).clickable { scope.launch { state.animateScrollToItem(i) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        options[i],
                        color = if (on) C.accent else C.sub.copy(alpha = 0.7f),
                        fontSize = if (on) 18.sp else 15.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** 고르는 칩 (하나만 켜짐) */
@Composable
private fun SelectChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (on) C.onAccent else C.text, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (on) C.accent else Color(0xFF1C2228))
            .border(1.dp, if (on) C.accent else C.line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** 이름표 + 한 줄 입력 칸 */
@Composable
private fun FormField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    number: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = C.sub, fontSize = 12.sp)
        val shape = RoundedCornerShape(12.dp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = C.text, fontSize = 15.sp),
            cursorBrush = SolidColor(C.accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
            modifier = Modifier.fillMaxWidth().clip(shape).background(Color(0xFF141922)).border(1.dp, C.line, shape).padding(horizontal = 12.dp, vertical = 11.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = C.sub.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1)
                inner()
            },
        )
    }
}

@Composable
private fun FormButtons(enabled: Boolean, onClose: () -> Unit, onAdd: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("닫기", Modifier.weight(1f), onClick = onClose)
        SmallButton("추가", Modifier.weight(1f), filled = enabled) { if (enabled) onAdd() }
    }
}

@Composable
private fun ReadinessCard(ui: PhoneUi) {
    val s = ui.status
    val run = s?.run
    val ready = run?.finished == true && run.allOk && ui.prepareSent
    Card {
        Text("출발 준비 상태", color = C.sub, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ReadyDot("보냄", ui.sent != null, Modifier.weight(1f))
            ReadyDot("차가 받음", ui.carReceived, Modifier.weight(1f))
            ReadyDot("준비됨", ready, Modifier.weight(1f))
        }
        // 한 줄로만: 지금 무슨 일이 일어나는지
        val line: Pair<String, Color>? = when {
            run != null && ui.prepareSent && run.countdown > 0 -> "${run.countdown}초 뒤 좌석이 움직여요 · 차 화면에서 취소할 수 있어요" to C.warn
            run != null && ui.prepareSent && !run.finished -> "좌석 맞추는 중 · ${run.done} / ${run.total}" to C.text
            run != null && ui.prepareSent && !run.allOk -> "맞추지 못한 좌석이 있어요 · 차 화면을 확인해 주세요" to C.warn
            ready -> "좌석 준비가 끝났어요" to C.ok
            s != null && s.pending > 0 -> "P단이 되면 좌석 ${s.pending}개를 맞춰요" to C.warn
            else -> null
        }
        line?.let { (t, c) -> Text(t, color = c, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun ReadyDot(label: String, on: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (on) C.accent else Color(0xFF1C2228)).border(1.dp, if (on) C.accent else C.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (on) "✓" else "…", color = if (on) C.onAccent else C.sub, fontSize = 13.sp)
        }
        Text(label, color = if (on) C.text else C.sub, fontSize = 12.sp)
    }
}

// ───────────────────────────── P-8 아기 상태 (동승 보호자) ─────────────────────────────

private data class BabyLook(val emoji: String, val title: String)

private fun babyLook(state: String?) = when (state) {
    "ASLEEP" -> BabyLook("🌙", "아기 자는 중")
    "AWAKE" -> BabyLook("👀", "아기 깨어 있음")
    "CRYING" -> BabyLook("😢", "아기가 울어요")
    "HUNGRY" -> BabyLook("🍼", "아기가 배고파해요")
    "DROWSY" -> BabyLook("😪", "아기가 졸려 해요")
    else -> BabyLook("👶", "아기 상태 확인 전")
}

@Composable
private fun RoadTab(ui: PhoneUi, vm: PhoneVm) {
    val s = ui.status
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TitleThin("ON THE ROAD", 26.sp)
        ConnectionLine(ui)
        if (s != null && !s.driving && !s.arrived) {
            Text("출발하면 여기서 아기 상태 · 다음 수유 · 휴게소를 봐요. 아기 상태는 지금도 알릴 수 있어요", color = C.sub, fontSize = 13.sp)
        }
        if (s != null) {
            val parts = listOfNotNull(
                s.gear.ifBlank { null }?.let { if (s.driving) "$it · ${s.speed} km/h" else "$it 정차 중" },
                s.remainMin?.let { "도착까지 ${hm(it)}" },
                s.babyEtaMin?.let { "아기와 함께 ${hm(it.toLong())}" },
            )
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = C.sub, fontSize = 13.sp)
        }

        if (s?.arrived == true) {
            Card(highlight = !s.rearAlert) {
                Text("도착했어요", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (s.rearAlert) "문을 잠갔어요 · 카시트에 아기가 있어요. 꼭 확인하세요" else "내리기 전에 뒷좌석 아기를 꼭 확인하세요",
                    color = if (s.rearAlert) C.danger else C.sub,
                    fontSize = 14.sp,
                    fontWeight = if (s.rearAlert) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }

        // 아기 상태
        val look = babyLook(s?.babyState)
        Card(highlight = true) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(88.dp).clip(CircleShape).background(Color(0xFF0B1418)).border(1.dp, C.accent.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(look.emoji, fontSize = 38.sp) }
                Text(look.title, color = C.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                if (s != null) {
                    val sub = listOfNotNull(
                        s.babyMinutes.takeIf { it >= 0 && s.babyState != "UNKNOWN" }?.let { "${it}분째" },
                        s.babySource.takeIf { s.babyState != "UNKNOWN" && it.isNotBlank() },
                    ) + s.comfort
                    if (sub.isNotEmpty()) Text(sub.joinToString(" · "), color = C.sub, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        }

        // 다음 수유
        Card {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("🍼", fontSize = 24.sp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val m = s?.minutesToFeed
                    Text(
                        when {
                            m == null -> "수유 기록이 없어요"
                            m >= 0 -> "다음 수유까지 ${hm(m.toLong())}"
                            else -> "수유 시간이 ${hm(-m.toLong())} 지났어요"
                        },
                        color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    )
                    s?.lastFeed?.let { Text("마지막 수유 $it", color = C.sub, fontSize = 12.sp) }
                }
                SmallButton("수유했어요", onClick = vm::fed)
            }
        }

        // 직접 알리기 (아기 상태 알리기 화면: 버튼 고르기 → [보내기])
        Text("카메라가 없거나 직접 알릴 때", color = C.sub, fontSize = 13.sp)
        DarkButton("아기 상태 알리기", onClick = vm::openCare)

        // 휴게소
        val rest = s?.rest
        if (rest != null) {
            Text(rest.reason, color = C.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (rest.candidates.isEmpty()) Text("경로 근처에 휴게소가 없어요", color = C.sub, fontSize = 13.sp)
            rest.candidates.forEachIndexed { i, c ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                (if (c.nursingRoom) "수유실 · " else "") + c.name,
                                color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                            )
                            Text("${"%.0f".format(c.km)}km · 약 ${c.eta}분", color = C.sub, fontSize = 12.sp)
                        }
                        if (rest.added == c.name) {
                            Text("경유지 추가됨", color = C.ok, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            SmallButton("경유하기", filled = true) { vm.addRestStop(i, c.name) }
                        }
                    }
                }
            }
        } else if (s?.driving == true) {
            SmallButton("수유실 있는 휴게소 찾기", Modifier.fillMaxWidth(), onClick = vm::findRestStops)
        }

        s?.voice?.let {
            Text("🔊 차 안내 · $it", color = C.sub, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun BabyButton(emoji: String, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) C.cardHi else C.card)
            .border(1.dp, if (selected) C.accent else C.line, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(emoji, fontSize = 22.sp)
        Text(label, color = if (selected) C.accent else C.text, fontSize = 13.sp)
    }
}

private fun hm(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}시간 ${m}분"
        h > 0 -> "${h}시간"
        else -> "${m}분"
    }
}

// ───────────────────────────── 주행 중 띠 · 아기 상태 알리기 ─────────────────────────────

/** 주행 중 띠를 보일 때: 차가 주행 중이라고 알리거나, 기어가 D · R · N (신호 대기로 멈춰 있어도) */
private fun onTheRoad(s: CarStatus?): Boolean = s != null && (s.driving || s.gear in listOf("D", "R", "N"))

/**
 * 아기 상태 알리기: 버튼 하나를 고르고 [보내기] → 차가 내비 옆에 알리고 할 일을 처리.
 * 버튼마다 차가 할 일(소리 · 온도 · 노래 · 근처 장소)은 AI 가 추천하고 보호자가 바꿀 수 있음.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CareScreen(ui: PhoneUi, vm: PhoneVm) {
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Long?>(null) }
    val picked = ui.careButtons.firstOrNull { it.id == ui.carePicked }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF1C2228)).clickable(onClick = vm::closeCare),
                    contentAlignment = Alignment.Center,
                ) { Text("‹", color = C.text, fontSize = 24.sp) }
                Column {
                    Text("아기 상태 알리기", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("고르고 [보내기]를 누르면 차로 가요", color = C.sub, fontSize = 13.sp)
                }
            }

            // 버튼 (두 개씩) + 버튼 추가
            val tiles: List<CareButton?> = ui.careButtons + null
            for (row in tiles.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (b in row) {
                        if (b == null) {
                            BabyButton("＋", "버튼 추가", adding, Modifier.weight(1f)) {
                                adding = !adding
                                editing = null
                            }
                        } else {
                            BabyButton(b.emoji, b.label, ui.carePicked == b.id, Modifier.weight(1f)) {
                                vm.pickCare(b.id)
                                editing = null
                                adding = false
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            if (adding) Card { CareAddForm(ui, vm) { adding = false } }

            if (picked != null) {
                Card(highlight = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${picked.emoji} ${picked.label} · 차가 할 일", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            if (ui.careThinking == picked.id) "추천받는 중…" else picked.source.labelKo,
                            color = if (picked.source == CareSource.AI) C.accent else C.sub, fontSize = 12.sp,
                        )
                    }
                    if (picked.steps.isEmpty()) {
                        Text("할 일이 없어요 · 차 화면 알림만 떠요", color = C.sub, fontSize = 14.sp)
                    } else {
                        for (s in picked.steps) Text("· ${s.labelKo}", color = C.text, fontSize = 14.sp)
                    }
                    Text("차 화면 내비 옆에 알림도 떠요", color = C.sub, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton("할 일 바꾸기") { editing = if (editing == picked.id) null else picked.id }
                        SmallButton(if (ui.aiInstalled) "AI 추천 다시" else "추천 다시") { vm.suggestCare(picked.id) }
                        if (picked.custom) SmallButton("버튼 지우기") { vm.removeCareButton(picked.id) }
                    }
                }
                if (editing == picked.id) Card { CareStepEditor(picked, vm) { editing = null } }
            }
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            if (picked == null) "버튼을 골라 주세요" else "보내기 · ${picked.label}",
            enabled = picked != null && ui.carOnline && !ui.busy && ui.careThinking == null,
            onClick = vm::sendCare,
        )
        if (!ui.carOnline) {
            Text("차와 연결되지 않았어요", color = C.danger, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

/** 버튼 추가: 이름 + 그림. 추가하면 할 일을 바로 추천받음 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CareAddForm(ui: PhoneUi, vm: PhoneVm, onClose: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf(CareDefaults.emojis.first()) }
    Text("새 버튼", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    FormField("이름", label, { label = it.take(12) }, "예: 똥 쌌어요, 더워해요, 토했어요")
    Text("그림", color = C.sub, fontSize = 12.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (e in CareDefaults.emojis) SelectChip(e, e == emoji) { emoji = e }
    }
    Text(
        if (ui.aiInstalled) "추가하면 AI가 차가 할 일을 추천해요 · 나중에 바꿀 수 있어요" else "추가하면 이름으로 차가 할 일을 추천해요 · 나중에 바꿀 수 있어요",
        color = C.sub, fontSize = 12.sp,
    )
    FormButtons(enabled = label.isNotBlank(), onClose = onClose) {
        if (vm.addCareButton(emoji, label)) onClose()
    }
}

/** 차가 할 일 직접 고르기 (여러 개, 반대 일은 하나만). 노래 · 장소는 값도 적음 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CareStepEditor(b: CareButton, vm: PhoneVm, onClose: () -> Unit) {
    val chosen = remember(b.id, b.steps) { mutableStateMapOf<CareAction, String>().apply { b.steps.forEach { put(it.action, it.value) } } }
    Text("차가 할 일 고르기", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (a in CareAction.entries) {
            SelectChip(a.labelKo, a in chosen) {
                if (a in chosen) {
                    chosen.remove(a)
                } else {
                    a.opposite?.let { chosen.remove(it) }
                    chosen[a] = a.defaultValue.orEmpty()
                }
            }
        }
    }
    for (a in CareAction.entries) {
        val hint = a.valueHint ?: continue
        if (a !in chosen) continue
        FormField(a.labelKo, chosen[a].orEmpty(), { chosen[a] = it.take(20) }, hint)
    }
    if (chosen.size > 3) Text("한 번에 3개까지만 보내요", color = C.warn, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("닫기", Modifier.weight(1f), onClick = onClose)
        SmallButton("저장", Modifier.weight(1f), filled = chosen.size <= 3) {
            if (chosen.size <= 3) {
                vm.setCareSteps(
                    b.id,
                    CareAction.entries.filter { it in chosen }.map { CareStep(it, chosen[it].orEmpty().trim().ifBlank { it.defaultValue.orEmpty() }) },
                )
                onClose()
            }
        }
    }
}
