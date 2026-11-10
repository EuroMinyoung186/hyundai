package ai.moduui.jari.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
private fun PrimaryButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val bg = if (enabled) Brush.horizontalGradient(listOf(C.accent, C.accentDeep)) else SolidColor(Color(0xFF232A31))
    Box(
        modifier
            .height(56.dp)
            .clip(shape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) C.onAccent else C.sub, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
        drawPath(p, Color(0xFFE8F7FD), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ───────────────────────────── P-1 시작 ─────────────────────────────

@Composable
fun StartScreen(onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.5f))
        Logo(190.dp)
        Spacer(Modifier.height(20.dp))
        Text("EVERY SEAT", color = C.text, fontSize = 26.sp, fontWeight = FontWeight.Light, letterSpacing = 8.sp)
        Spacer(Modifier.height(6.dp))
        Text("모두의 자리", color = C.sub, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text("아기 자리는 미리,", color = C.text, fontSize = 19.sp)
        Text("운전은 편하게.", color = C.accent, fontSize = 19.sp)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("시작하기", onClick = onStart)
        Spacer(Modifier.height(12.dp))
        Text("Google · 카카오 로그인은 다음 버전 · 지금은 차와 바로 연결해요", color = C.sub, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

// ───────────────────────────── P-2 차 연결 ─────────────────────────────

@Composable
fun ConnectScreen(ui: PhoneUi, vm: PhoneVm) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF1C2228)).clickable(onClick = vm::backToStart),
            contentAlignment = Alignment.Center,
        ) { Text("‹", color = C.text, fontSize = 24.sp) }
        TitleThin("CONNECT")
        Row {
            Text("차 화면에서 ", color = C.text, fontSize = 15.sp)
            Text("모두의 자리", color = C.accent, fontSize = 15.sp)
            Text("를 열면", color = C.text, fontSize = 15.sp)
        }
        Text("오른쪽 위에 6자리 연결 코드가 보여요.", color = C.text, fontSize = 15.sp)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = ui.codeInput,
            onValueChange = vm::setCodeInput,
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
                            val ch = ui.codeInput.getOrNull(i)
                            val active = i == ui.codeInput.length
                            val shape = RoundedCornerShape(14.dp)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(0.82f)
                                    .clip(shape)
                                    .background(C.card)
                                    .border(1.dp, if (active) C.accent else C.line, shape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(ch?.toString() ?: "", color = C.text, fontSize = 24.sp)
                            }
                        }
                    }
                }
            },
        )
        ui.connectError?.let { Text(it, color = C.danger, fontSize = 13.sp) }
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            if (ui.connecting) "연결하는 중…" else "연결하기",
            enabled = ui.codeInput.length == 6 && !ui.connecting,
            onClick = vm::connect,
        )
    }
}

// ───────────────────────────── 연결 후 (홈 · 주행 중) ─────────────────────────────

@Composable
fun MainScreen(ui: PhoneUi, vm: PhoneVm, listen: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (ui.tab) {
                Tab.HOME -> HomeTab(ui, vm, listen)
                Tab.ROAD -> RoadTab(ui, vm)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF14181D))
                .padding(6.dp),
        ) {
            NavItem("홈", ui.tab == Tab.HOME, Modifier.weight(1f)) { vm.selectTab(Tab.HOME) }
            NavItem("주행 중", ui.tab == Tab.ROAD, Modifier.weight(1f)) { vm.selectTab(Tab.ROAD) }
        }
    }
}

@Composable
private fun NavItem(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) C.cardHi else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) C.accent else C.sub, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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

private val DESTINATIONS = listOf("강릉", "속초", "부산", "할머니댁")

@Composable
private fun HomeTab(ui: PhoneUi, vm: PhoneVm, listen: () -> Unit) {
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
        Card {
            Text("다음 여정", color = C.sub, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val names = (DESTINATIONS + ui.draft.destination).distinct()
                for (name in names.take(5)) {
                    val on = name == ui.draft.destination
                    Text(
                        name,
                        color = if (on) C.onAccent else C.text,
                        fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) C.accent else Color(0xFF1C2228))
                            .clickable { vm.edit { d -> d.copy(destination = name) } }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }

        // 1-C 말하기
        VoiceCard(ui, vm, listen)

        // 인원 · 짐
        Text(
            if (ui.heard.isNotEmpty()) "이렇게 알아들었어요 · 틀린 것만 고쳐 주세요" else "숫자를 눌러 직접 넣어도 돼요",
            color = if (ui.heard.isNotEmpty()) C.accent else C.sub, fontSize = 13.sp,
        )
        val d = ui.draft
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountTile("어르신", d.seniors, Slot.SENIOR in ui.heard, Modifier.weight(1f)) { n -> vm.edit { it.copy(seniors = n) } }
            CountTile("어른", d.adults, Slot.ADULT in ui.heard, Modifier.weight(1f)) { n -> vm.edit { it.copy(adults = n) } }
            CountTile("아이", d.children, Slot.CHILD in ui.heard, Modifier.weight(1f)) { n -> vm.edit { it.copy(children = n) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountTile("아기", d.infants, Slot.INFANT in ui.heard, Modifier.weight(1f), max = 3) { n -> vm.edit { it.copy(infants = n) } }
            CountTile("유모차", d.strollers, Slot.STROLLER in ui.heard, Modifier.weight(1f)) { n -> vm.edit { it.copy(strollers = n) } }
            CountTile("캐리어", d.suitcases, Slot.SUITCASE in ui.heard, Modifier.weight(1f)) { n -> vm.edit { it.copy(suitcases = n) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CountTile("아기 짐가방", d.babyBags, Slot.BABY_BAG in ui.heard, Modifier.weight(1f)) { n -> vm.edit { it.copy(babyBags = n) } }
            Column(
                Modifier
                    .weight(2f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(C.card)
                    .border(1.dp, C.line, RoundedCornerShape(16.dp))
                    .clickable { vm.edit { it.copy(caregiverNextToBaby = !it.caregiverNextToBaby) } }
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("보호자 자리", color = C.sub, fontSize = 12.sp)
                Text(
                    if (d.caregiverNextToBaby) "✓ 아기 옆에 앉아요" else "아기 옆이 아니어도 돼요",
                    color = if (d.caregiverNextToBaby) C.accent else C.text, fontSize = 14.sp,
                )
            }
        }

        PrimaryButton(
            when {
                ui.sent == null -> "차로 보내기"
                ui.draftChanged -> "고친 내용 다시 보내기"
                else -> "다시 보내기"
            },
            enabled = ui.carOnline && !ui.busy,
            onClick = vm::sendTrip,
        )

        // 차가 세운 추천 계획
        if (ui.carReceived && s?.planSummary != null) {
            Card(highlight = true) {
                Text("출발 계획 추천 · 차에서 받음", color = C.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(s.planSummary, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                for (n in s.planNotes.take(3)) Text("· $n", color = C.sub, fontSize = 13.sp)
                Text("차가 맞추는 일 ${s.planSdk}개 · 사람이 할 일 ${s.planGuide}개", color = C.sub, fontSize = 12.sp)
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
        Text("좌석은 P단에서만 움직여요 · 문이 열리면 바로 멈춰요", color = C.sub, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(
            "연결 해제", color = C.sub, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(onClick = vm::disconnect).padding(8.dp),
        )
    }
}

@Composable
private fun VoiceCard(ui: PhoneUi, vm: PhoneVm, listen: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF1E2430))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(C.accentDeep).clickable(onClick = listen),
            contentAlignment = Alignment.Center,
        ) { MicIcon(30.dp) }
        Text("누가 타고 무엇을 싣나요? 눌러서 말해 주세요", color = C.sub, fontSize = 13.sp)
        Text(
            "“${ui.heardText ?: "어머니랑 애 둘, 유모차랑 캐리어 하나"}”",
            color = if (ui.heardText != null) C.text else C.sub,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shape = RoundedCornerShape(14.dp)
            BasicTextField(
                value = typed,
                onValueChange = { typed = it },
                singleLine = true,
                textStyle = TextStyle(color = C.text, fontSize = 14.sp),
                cursorBrush = SolidColor(C.accent),
                modifier = Modifier.weight(1f).clip(shape).background(Color(0xFF141922)).padding(horizontal = 12.dp, vertical = 11.dp),
                decorationBox = { inner ->
                    if (typed.isEmpty()) Text("말 대신 적어도 돼요", color = C.sub, fontSize = 14.sp)
                    inner()
                },
            )
            SmallButton("알아듣기") {
                if (typed.isNotBlank()) vm.onHeard(typed)
            }
        }
    }
}

@Composable
private fun MicIcon(size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val sw = w * 0.08f
        val capW = w * 0.32f
        val capL = (w - capW) / 2
        drawRoundRect(
            Color.White,
            topLeft = Offset(capL, w * 0.08f),
            size = androidx.compose.ui.geometry.Size(capW, w * 0.52f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(capW / 2),
        )
        drawArc(
            Color.White, 0f, 180f, false,
            topLeft = Offset(w * 0.2f, w * 0.26f),
            size = androidx.compose.ui.geometry.Size(w * 0.6f, w * 0.5f),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
        drawLine(Color.White, Offset(w / 2, w * 0.76f), Offset(w / 2, w * 0.92f), sw, StrokeCap.Round)
    }
}

@Composable
private fun CountTile(label: String, value: Int, highlight: Boolean, modifier: Modifier, max: Int = 6, onChange: (Int) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(C.card)
            .border(1.dp, if (highlight) C.accent else C.line, shape)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = C.sub, fontSize = 12.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepBtn("−") { onChange((value - 1).coerceAtLeast(0)) }
            Text("$value", color = C.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.width(26.dp))
            StepBtn("+") { onChange((value + 1).coerceAtMost(max)) }
        }
    }
}

@Composable
private fun StepBtn(text: String, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = C.accent, fontSize = 18.sp)
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
        if (run != null && ui.prepareSent) {
            if (run.countdown > 0) {
                Text("${run.countdown}초 뒤 좌석이 움직여요 · 차 화면에서 취소할 수 있어요", color = C.warn, fontSize = 13.sp)
            } else {
                Text("${run.reason} · ${run.done} / ${run.total}", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            for (st in run.steps) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(st.label, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(
                        st.outcome,
                        color = when {
                            !st.terminal -> C.sub
                            st.ok -> C.accent
                            else -> C.warn
                        },
                        fontSize = 12.sp,
                    )
                }
            }
            if (run.finished && !run.allOk) {
                Text("맞추지 못한 좌석이 있어요 · 차 화면을 확인하고 다시 눌러 주세요", color = C.warn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        } else if (s != null && s.pending > 0) {
            Text("P단이 되면 좌석 ${s.pending}개를 맞춰요", color = C.warn, fontSize = 13.sp)
        }
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

        // 직접 알리기
        Text("카메라가 없거나 직접 알릴 때", color = C.sub, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BabyButton("🌙", "잠들었어요", s?.babyState == "ASLEEP", Modifier.weight(1f)) { vm.baby("ASLEEP", "잠들었어요") }
            BabyButton("👀", "깼어요", s?.babyState == "AWAKE", Modifier.weight(1f)) { vm.baby("AWAKE", "깼어요") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BabyButton("😢", "울어요", s?.babyState == "CRYING", Modifier.weight(1f)) { vm.baby("CRYING", "울어요") }
            BabyButton("🍼", "배고파해요", s?.babyState == "HUNGRY", Modifier.weight(1f)) { vm.baby("HUNGRY", "배고파해요") }
        }

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
