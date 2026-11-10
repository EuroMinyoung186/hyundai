package ai.moduui.jari.ui.screens

import ai.moduui.core.model.LuggageType
import ai.moduui.core.model.SeatId
import ai.moduui.core.model.Side
import ai.moduui.core.model.TripRequest
import ai.moduui.core.seat.Executor
import ai.moduui.core.seat.Occupant
import ai.moduui.core.seat.SeatAction
import ai.moduui.core.seat.SeatAxis
import ai.moduui.core.seat.SeatPlan
import ai.moduui.core.seat.SeatStep
import ai.moduui.jari.control.PhoneStatus
import ai.moduui.jari.control.StepOutcome
import ai.moduui.jari.control.UiState
import ai.moduui.jari.ui.MainViewModel
import ai.moduui.jari.ui.Screen
import ai.moduui.jari.ui.components.Chip
import ai.moduui.jari.ui.components.CircleIconButton
import ai.moduui.jari.ui.components.DesignToggle
import ai.moduui.jari.ui.components.GhostButton
import ai.moduui.jari.ui.components.GlassCar3D
import ai.moduui.jari.ui.components.GlassCarSide
import ai.moduui.jari.ui.components.GlassSeat
import ai.moduui.jari.ui.components.GradientButton
import ai.moduui.jari.ui.components.HoldApplyButton
import ai.moduui.jari.ui.components.Ic
import ai.moduui.jari.ui.components.KnobButton
import ai.moduui.jari.ui.components.LineIcon
import ai.moduui.jari.ui.components.MainMap
import ai.moduui.jari.ui.components.ModeChip
import ai.moduui.jari.ui.components.SeatLook
import ai.moduui.jari.ui.components.SegmentedChoice
import ai.moduui.jari.ui.components.StatPill
import ai.moduui.jari.ui.components.TitleMont
import ai.moduui.jari.ui.components.designCard
import ai.moduui.jari.ui.hm
import ai.moduui.jari.ui.theme.DarkPalette
import ai.moduui.jari.ui.theme.LocalPalette
import ai.moduui.jari.ui.theme.MapColors
import ai.moduui.jari.ui.theme.Montserrat
import ai.moduui.jari.vehicle.MoveDirection
import ai.moduui.jari.vehicle.SeatFeature
import ai.moduui.jari.vehicle.Support
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ───────────────────────────── 홈 (디자인 A-2 CarHome) ─────────────────────────────

@Composable
fun HomeScreen(ui: UiState, vm: MainViewModel, go: (Screen) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        if (w >= 1000.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                HomeHero(ui, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(minOf(600.dp, w * 0.46f)).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    NextDepartureCard(ui)
                    QuickSeatCard(ui, vm, go, Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            // 세로(1 window): 위에서 아래로 쌓음
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                HomeHero(ui, Modifier.fillMaxWidth().height(440.dp))
                NextDepartureCard(ui)
                QuickSeatCard(ui, vm, go, Modifier.fillMaxWidth().height(250.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeHero(ui: UiState, modifier: Modifier) {
    val p = LocalPalette.current
    val plan = ui.plan
    Box(modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 4.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TitleMont("WELCOME", 52.sp)
            Text(
                buildAnnotatedString {
                    append("오늘은 누가 타나요? · ")
                    append(if (ui.isDriving) "주행 중" else "${ui.driving.gear ?: "P"} 정차 중")
                    if (ui.simulation) {
                        append(" · ")
                        withStyle(SpanStyle(color = p.warn)) { append("시뮬레이션") }
                    }
                },
                color = p.sub, fontSize = 16.sp,
            )
        }
        PhonePill(ui.phone, Modifier.align(Alignment.TopEnd))
        GlassCarSide(
            Modifier
                .align(BiasAlignment(0f, 0.25f))
                .fillMaxWidth(0.92f)
                .aspectRatio(840f / 260f),
        )
        FlowRow(
            Modifier.align(Alignment.BottomStart),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val folds = plan?.steps?.count { it.action == SeatAction.Fold } ?: 0
            StatPill("SEATS", plan?.let { "바뀜 ${it.sdkSteps.size} · 접기 $folds" } ?: "세팅 전")
            val heats = plan?.steps.orEmpty().filter { it.action is SeatAction.Heat }
            StatPill(
                "CLIMATE",
                when {
                    plan == null -> "-"
                    heats.isEmpty() -> "변경 없음"
                    heats.size == 1 -> "${heats[0].seat.labelKo} ${heats[0].action.labelKo}"
                    else -> "${heats[0].seat.labelKo} 외 ${heats.size - 1}"
                },
            )
            StatPill("AUTO", "도착하면 하차 준비", p.accentText)
        }
    }
}

/** 디자인 C-1a/C-1b: 휴대폰 연결 코드 → 연결됨 */
@Composable
private fun PhonePill(s: PhoneStatus, modifier: Modifier) {
    val p = LocalPalette.current
    val code = s.code ?: return
    Column(
        modifier.designCard(p).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (s.connected) {
            Text("● 휴대폰 연결됨", color = p.accentText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("보호자 휴대폰에서 여정을 받아요", color = p.sub, fontSize = 12.sp)
        } else {
            Text("휴대폰 연결 코드", color = p.sub, fontSize = 12.sp)
            Text(code.chunked(3).joinToString(" "), color = p.text, fontSize = 28.sp, fontFamily = Montserrat, letterSpacing = 3.sp)
            Text(
                if (s.serverReachable) "모두의 자리 휴대폰 앱에 입력하세요" else "중계 서버에 연결 안 됨",
                color = if (s.serverReachable) p.sub else p.warn, fontSize = 12.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NextDepartureCard(ui: UiState) {
    val p = LocalPalette.current
    val trip = ui.trip
    val route = ui.navi.route
    Column(
        Modifier.fillMaxWidth().designCard(p).padding(horizontal = 24.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("다음 출발", color = p.sub, fontSize = 14.sp, modifier = Modifier.weight(1f))
            val trailing = listOfNotNull(
                if (trip != null) "여정 받음" else null,
                ui.departureAdvice?.let { "추천 출발 ${it.departAt.hm()}" } ?: ui.nextFeedAt?.let { "다음 수유 ${it.hm()}" },
            ).joinToString(" · ")
            if (trailing.isNotEmpty()) Text(trailing, color = p.accentText, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                trip?.destinationName ?: route?.destinationName ?: "목적지 미정",
                color = p.text, fontSize = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            route?.let { r ->
                Text(routeText(r.remainSeconds, r.remainMeters), color = p.sub, fontSize = 16.sp, maxLines = 1, modifier = Modifier.alignByBaseline())
            }
        }
        if (trip != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tripChips(trip).forEach { Chip(it) }
            }
        } else {
            Text("Gleo 에게 말하거나 [말로 바꾸기]로 타는 사람과 짐을 알려 주세요", color = p.sub, fontSize = 14.sp)
        }
    }
}

@Composable
private fun QuickSeatCard(ui: UiState, vm: MainViewModel, go: (Screen) -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    val plan = ui.plan
    Column(
        modifier.designCard(p).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("간편 자리 맞춤", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KnobButton("말로 바꾸기", Ic.MIC, { go(Screen.TRIP) })
            KnobButton("3D 보기", Ic.CUBE, { go(Screen.BOARDING) })
            // 홈에서는 좌석을 바로 움직이지 않음: 세팅 화면에서 누르고 있는 동안만 맞춤
            KnobButton("지금 적용", Ic.POWER, { go(if (plan != null) Screen.BOARDING else Screen.TRIP) }, big = true)
            KnobButton("원래대로", Ic.UNDO, { vm.controller.runner.undoLast() }, enabled = ui.run?.canUndo == true && !ui.isDriving)
        }
    }
}

fun routeText(remainSeconds: Long, remainMeters: Long?): String =
    listOfNotNull(durationKo((remainSeconds / 60).toInt()), remainMeters?.let { "${it / 1000}km" }).joinToString(" · ")

fun durationKo(minutes: Int): String = when {
    minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60}시간"
    minutes >= 60 -> "${minutes / 60}시간 ${minutes % 60}분"
    else -> "${minutes.coerceAtLeast(0)}분"
}

fun tripChips(t: TripRequest): List<String> = buildList {
    if (t.seniors > 0) add("어르신 ${t.seniors}")
    if (t.adults > 0) add("어른 ${t.adults}")
    if (t.children > 0) add("아이 ${t.children}")
    if (t.infants > 0) add("아기 ${t.infants}")
    t.luggage.filterValues { it > 0 }.forEach { (k, v) -> add(if (v == 1) k.labelKo else "${k.labelKo} $v") }
}

// ───────────────────────────── 말로 바꾸기 (디자인 1-A 지도 위 탑승 카드) ─────────────────────────────

@Composable
fun TripEditScreen(ui: UiState, vm: MainViewModel, go: (Screen) -> Unit) {
    val confirm: (TripRequest) -> Unit = { t ->
        vm.controller.planTrip(t)
        go(Screen.BOARDING)
    }
    if (!ui.simulation) {
        // 실제 내비가 있으면 지도는 내비가 보여 줌 → 카드만
        BoxWithConstraints(Modifier.fillMaxSize()) {
            TripCard(ui, Modifier.align(Alignment.TopCenter).width(minOf(720.dp, maxWidth)), onConfirm = confirm)
        }
        return
    }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp))) {
        MainMap(Modifier.matchParentSize())
        RouteBar(ui, Modifier.align(Alignment.BottomCenter))
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 28.dp, top = 28.dp, end = 28.dp, bottom = 92.dp)) {
            TripCard(ui, Modifier.width(minOf(720.dp, maxWidth)), onConfirm = confirm)
        }
    }
}

/** 디자인 1-A 탑승 카드. 다크/일반 모드와 상관없이 어두운 카드. 내비 옆 창과 앱 전체 창에서 같이 씀. */
@Composable
fun TripCard(ui: UiState, modifier: Modifier = Modifier, confirmLabel: String = "세팅 보기", onConfirm: (TripRequest) -> Unit) {
    val heard = ui.trip ?: TripRequest()
    var t by remember(ui.trip) { mutableStateOf(heard) }
    fun lug(type: LuggageType) = t.luggage[type] ?: 0
    fun setLug(type: LuggageType, n: Int) {
        t = t.copy(luggage = t.luggage + (type to n))
    }
    CompositionLocalProvider(LocalPalette provides DarkPalette) {
        Column(
            modifier
                .shadow(24.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1E2430))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(MapColors.green).semantics { contentDescription = "다시 말하기" },
                    contentAlignment = Alignment.Center,
                ) { LineIcon(Ic.MIC, Color.White, size = 26.dp, stroke = 2f) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when {
                            ui.trip == null -> "누가 타고 무엇을 싣나요? Gleo 에게 말로 알려 주세요"
                            ui.tripSource == "휴대폰" -> "📱 보호자 휴대폰에서 받은 내용"
                            else -> "Gleo 에게 들은 내용"
                        },
                        color = MapColors.overlaySub, fontSize = 14.sp,
                    )
                    Text(heardSentence(ui.trip), color = Color(0xFFF3F4F6), fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                }
            }
            Text("이렇게 알아들었어요 · 틀린 것만 고쳐 주세요", color = MapColors.mint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            val tiles = listOf(
                Triple("어르신", t.seniors, heard.seniors) to { n: Int -> t = t.copy(seniors = n) },
                Triple("어른 (운전자 제외)", t.adults, heard.adults) to { n: Int -> t = t.copy(adults = n) },
                Triple("아이", t.children, heard.children) to { n: Int -> t = t.copy(children = n) },
                Triple("아기 (카시트)", t.infants, heard.infants) to { n: Int -> t = t.copy(infants = n.coerceAtMost(3)) },
                Triple("캐리어", lug(LuggageType.SUITCASE), heard.luggage[LuggageType.SUITCASE] ?: 0) to { n: Int -> setLug(LuggageType.SUITCASE, n) },
                Triple("유모차", lug(LuggageType.STROLLER), heard.luggage[LuggageType.STROLLER] ?: 0) to { n: Int -> setLug(LuggageType.STROLLER, n) },
            )
            for (row in tiles.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((v, set) in row) {
                        CountTile(v.first, v.second, v.second != v.third, set, Modifier.weight(1f))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("보호자가 아기 옆", color = Color(0xFFF3F4F6), fontSize = 15.sp)
                DesignToggle(t.caregiverNextToBaby, { t = t.copy(caregiverNextToBaby = it) }, description = "보호자가 아기 옆에 앉아요")
                Box(Modifier.weight(1f))
                Text("좁은 쪽", color = Color(0xFFF3F4F6), fontSize = 15.sp)
                SegmentedChoice(
                    listOf("없음", "왼쪽", "오른쪽"),
                    when (t.tightParkingSide) { null -> 0; Side.LEFT -> 1; Side.RIGHT -> 2 },
                    { i -> t = t.copy(tightParkingSide = when (i) { 1 -> Side.LEFT; 2 -> Side.RIGHT; else -> null }) },
                    Modifier.width(240.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("휴대폰에서 미리 말했다면 여기 자동으로 채워져요", color = MapColors.overlaySub, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .height(60.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MapColors.green)
                        .clickable(role = Role.Button) { onConfirm(t) }
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(confirmLabel, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}

private fun heardSentence(t: TripRequest?): String {
    if (t == null) return "“모두의 자리, 유모차랑 캐리어 두 개 싣고 할머니랑 강릉 가”"
    val parts = listOfNotNull(t.destinationName) + tripChips(t)
    return "“${parts.joinToString(" · ")}”"
}

@Composable
private fun CountTile(label: String, n: Int, changed: Boolean, onChange: (Int) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(MapColors.tile)
            .border(if (changed) 2.dp else 0.dp, if (changed) MapColors.mint else Color.Transparent, shape)
            .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = Color(0xFFF3F4F6), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TileButton("−", "$label 줄이기", n > 0) { onChange(n - 1) }
            Text("$n", color = Color(0xFFF3F4F6), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            TileButton("+", "$label 늘리기", n < 6) { onChange(n + 1) }
        }
    }
}

@Composable
private fun TileButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MapColors.tileButton.copy(alpha = if (enabled) 1f else 0.4f))
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(symbol, color = Color(0xFFF3F4F6), fontSize = 24.sp) }
}

/** 지도 아래 어두운 띠: 도착 시각 · 남은 시간 · 기어 */
@Composable
fun RouteBar(ui: UiState, modifier: Modifier = Modifier, left: String? = null, hint: String? = null) {
    val r = ui.navi.route
    Row(
        modifier.fillMaxWidth().height(60.dp).background(MapColors.overlay).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(left ?: (r?.let { "도착 ${ui.now.plusSeconds(it.remainSeconds).hm()}" } ?: "경로 없음"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(hint ?: (r?.let { routeText(it.remainSeconds, it.remainMeters) } ?: "내비에서 목적지를 정하면 여기에 보여요"), color = MapColors.overlaySub, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Text(if (ui.isDriving) "주행 중 ${ui.driving.gear ?: ""}" else "${ui.driving.gear ?: "P"} 정차 중", color = MapColors.overlaySub, fontSize = 16.sp, maxLines = 1)
    }
}

// ───────────────────────────── 세팅 (디자인 C-2 BOARDING) ─────────────────────────────

private data class BoardItem(
    val step: SeatStep,
    val guide: Boolean,
    val done: Boolean,
    val outcome: StepOutcome?,
    val detail: String?,
    val unsupported: Boolean,
) {
    /** 더 할 일이 없음: 끝났거나 이 차에 없는 기능 */
    val resolved: Boolean get() = done || outcome == StepOutcome.UNSUPPORTED
}

@Composable
fun BoardingScreen(ui: UiState, vm: MainViewModel, go: (Screen) -> Unit) {
    val p = LocalPalette.current
    val plan = ui.plan
    if (plan == null) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TitleMont("BOARDING", 34.sp)
            Text("아직 이번 여정을 몰라요. Gleo 에게 말하거나 [말로 바꾸기]로 알려 주세요", color = p.sub, fontSize = 17.sp)
            GradientButton("말로 바꾸기", { go(Screen.TRIP) }, icon = Ic.MIC)
        }
        return
    }
    var locked by rememberSaveable { mutableStateOf(false) }
    val guideDone = remember(plan) { mutableStateListOf<Int>() }
    var selected by remember(plan) { mutableIntStateOf(0) }

    val items = plan.steps.mapIndexed { i, step ->
        val guide = step.executor == Executor.GUIDE
        val st = ui.run?.steps?.firstOrNull { it.step == step }
        BoardItem(
            step = step,
            guide = guide,
            done = if (guide) i in guideDone else st?.outcome == StepOutcome.DONE || st?.outcome == StepOutcome.SENT,
            outcome = st?.outcome,
            detail = st?.detail,
            unsupported = !guide && support(ui, step) == Support.UNSUPPORTED,
        )
    }
    val seats = glassSeats(plan, items, ui)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        if (w >= 1100.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BoardingStage(ui, plan, seats, locked, { locked = !locked }, Modifier.weight(1f).fillMaxHeight())
                BoardingChecklist(ui, vm, plan, items, guideDone, selected, { selected = it }, Modifier.width(minOf(600.dp, w * 0.44f)).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BoardingStage(ui, plan, seats, locked, { locked = !locked }, Modifier.fillMaxWidth().weight(0.9f))
                BoardingChecklist(ui, vm, plan, items, guideDone, selected, { selected = it }, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

private fun glassSeats(plan: SeatPlan, items: List<BoardItem>, ui: UiState): List<GlassSeat> = SeatId.entries.map { seat ->
    val mine = items.filter { it.step.seat == seat }
    val folded = mine.any { it.step.action == SeatAction.Fold && it.done }
    val look = when {
        mine.any { !it.guide && !it.resolved } -> SeatLook.ADJUST
        mine.any { it.step.action == SeatAction.Fold && !it.done } -> SeatLook.FOLD
        plan.assignments[seat] == Occupant.INFANT_CAR_SEAT -> SeatLook.CAR_SEAT
        else -> SeatLook.NORMAL
    }
    // 위치는 0..100 (시뮬레이터가 실시간으로 알려 줌). 등받이: 클수록 곧게 → 40°..10°
    // 실차 SDK 는 실시간 위치를 주지 않으므로, 끝난 단계는 목표값으로 보여 줌
    fun doneTarget(axis: SeatAxis) = mine.firstOrNull { it.done && (it.step.action as? SeatAction.Move)?.axis == axis }
        ?.let { (it.step.action as SeatAction.Move).targetFraction }
    val back = ui.vehicle.positions[seat to SeatAxis.BACKREST]?.div(100f) ?: doneTarget(SeatAxis.BACKREST)
    val fore = ui.vehicle.positions[seat to SeatAxis.FORE_AFT]?.div(100f) ?: doneTarget(SeatAxis.FORE_AFT)
    GlassSeat(
        seat = seat,
        look = look,
        reclineDeg = when {
            folded -> -80f
            back != null -> 40f - back * 30f
            else -> 22f
        },
        slide = fore?.let { (0.5f - it) * 40f } ?: 0f,
    )
}

@Composable
private fun BoardingStage(ui: UiState, plan: SeatPlan, seats: List<GlassSeat>, locked: Boolean, toggleLock: () -> Unit, modifier: Modifier) {
    val p = LocalPalette.current
    Box(modifier) {
        GlassCar3D(seats, locked, Modifier.fillMaxSize().padding(top = 70.dp, bottom = 50.dp))
        Column(Modifier.align(Alignment.TopStart).padding(start = 4.dp, end = 160.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TitleMont("BOARDING", 34.sp)
            Text("${ui.trip?.destinationName ?: "오늘 여정"} · ${plan.summaryKo}", color = p.sub, fontSize = 14.sp, maxLines = 2)
        }
        Row(Modifier.align(Alignment.TopEnd), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.simulation) ModeChip("시뮬레이션", p.warn) else ModeChip("실제 데이터", p.accentText)
            CircleIconButton(if (locked) Ic.LOCK else Ic.REFRESH, if (locked) "회전 고정 풀기" else "회전 고정", toggleLock, selected = locked)
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .height(40.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(p.railBg)
                .border(1.dp, p.railBorder, RoundedCornerShape(999.dp))
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LegendMark(p.accent, dashed = false, glow = true, "맞춰야 할 자리")
            LegendMark(p.text, dashed = true, glow = false, "카시트 좌석")
            LegendMark(p.warn, dashed = true, glow = false, "접을 좌석 · 안내")
            Text("끌어서 돌려 보기", color = p.muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LegendMark(color: Color, dashed: Boolean, glow: Boolean, label: String) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(16.dp, 10.dp).drawBehind {
                val st = Stroke(2.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) else null)
                if (glow) drawRoundRect(color.copy(alpha = 0.35f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(5.dp.toPx()))
                drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = st)
            },
        )
        Text(label, color = p.chipText, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun BoardingChecklist(
    ui: UiState,
    vm: MainViewModel,
    plan: SeatPlan,
    items: List<BoardItem>,
    guideDone: MutableList<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    val p = LocalPalette.current
    val doneCount = items.count { it.resolved }
    val allDone = items.isNotEmpty() && doneCount == items.size
    val run = ui.run
    Column(modifier.padding(start = 8.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("현재 상태 확인 · 다른 것만 맞춰요", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    "${if (ui.isDriving) "주행 중" else "${ui.driving.gear ?: "P"} 정차 중"} · 좌석 값을 읽어 오늘 세팅과 비교해요 · 항목을 누르면 아래에서 조절",
                    color = p.sub, fontSize = 13.sp, maxLines = 2,
                )
            }
            Text(
                buildAnnotatedString {
                    append("$doneCount")
                    withStyle(SpanStyle(color = p.sub, fontSize = 16.sp)) { append(" / ${items.size}") }
                },
                color = p.accentText, fontFamily = Montserrat, fontWeight = FontWeight.Light, fontSize = 28.sp,
            )
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items.forEachIndexed { i, item ->
                CheckItem(i + 1, item, selected == i, onClick = { onSelect(i) }, onAction = {
                    if (item.guide) {
                        if (i in guideDone) guideDone.remove(i) else guideDone.add(i)
                    } else {
                        vm.controller.refreshCapabilities()
                    }
                })
            }
            plan.notesKo.forEach { Text("· $it", color = p.warn, fontSize = 13.sp) }
            if (ui.pendingSteps.isNotEmpty()) Text("P단이 확인되면 ${ui.pendingSteps.size}단계를 실행해요", color = p.warn, fontSize = 13.sp)
        }

        items.getOrNull(selected)?.let { SelectedSeatBox(ui, vm, it.step.seat) }

        val countdown = run?.countdown
        when {
            countdown != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${countdown}초 뒤 좌석이 움직여요 · 좌석 주변을 확인하세요", color = p.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                GhostButton("멈추기", { vm.controller.runner.cancel() }, height = 48.dp, fontSize = 15.sp)
            }
            allDone -> Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(p.knobOnTop, p.knobOnBottom)))
                    .border(1.dp, p.accentRing, RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val carSeat = plan.carSeatSeats.firstOrNull()?.labelKo
                Text(
                    "준비 끝 · ${carSeat?.let { "아기를 $it 카시트에 태우면 돼요." } ?: "타면 돼요."} 도착하면 하차 준비를 알려 드릴게요.",
                    color = p.text, fontSize = 14.sp,
                )
                if (run?.canUndo == true) GhostButton("되돌리기", { vm.controller.runner.undoLast() }, Modifier.padding(top = 8.dp), height = 40.dp, fontSize = 13.sp)
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HoldApplyButton(
                        idleText = if (ui.isDriving) "주행 중에는 움직이지 않아요" else "적용 · 누르고 있으면 맞춰요",
                        holdingText = "맞추는 중… 손을 떼면 멈춰요",
                        enabled = !ui.isDriving && plan.sdkSteps.isNotEmpty(),
                        onStart = { vm.controller.holdApplyStart() },
                        onStop = { vm.controller.holdApplyStop() },
                        modifier = Modifier.weight(1f),
                    )
                    if (run?.canUndo == true) GhostButton("되돌리기", { vm.controller.runner.undoLast() }, height = 48.dp, fontSize = 14.sp)
                    if (ui.simulation) DashedChip("시뮬레이션")
                }
                Text(
                    "Vehicle SDK 좌석 제어 · 누르고 있는 동안만 · P · 빈 자리만 · 접기는 안내",
                    color = p.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CheckItem(n: Int, item: BoardItem, selected: Boolean, onClick: () -> Unit, onAction: () -> Unit) {
    val p = LocalPalette.current
    val it = item
    val shape = RoundedCornerShape(20.dp)
    val sub = when {
        it.done -> it.detail ?: if (it.guide) "직접 확인했어요" else "맞춤 완료"
        it.outcome == StepOutcome.CANCELLED -> "멈춤 · 다시 누르면 이어서 맞춰요"
        it.outcome != null && it.outcome != StepOutcome.PENDING -> it.outcome.labelKo
        else -> it.step.reasonKo
    }
    val level = when {
        it.guide -> "안내 · 직접 체크"
        it.unsupported && !it.done -> "실제 · 미지원 예상"
        else -> "실제 · 자동 확인"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (selected) Modifier.border(2.dp, p.accent.copy(alpha = 0.55f), shape) else Modifier)
            .designCard(p, 20.dp)
            .border(1.dp, if (it.done) p.cardBorder else p.accentRing, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (it.done) Brush.verticalGradient(listOf(p.accent, p.accentDark))
                    else Brush.verticalGradient(listOf(p.knobOnTop, p.knobOnBottom)),
                )
                .border(1.dp, if (it.done) Color.Transparent else p.knobOnRing, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (it.done) LineIcon(Ic.CHECK, p.onAccent, size = 18.dp, stroke = 3f)
            else Text("$n", color = p.accentText, fontFamily = Montserrat, fontSize = 15.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${it.step.seat.labelKo} · ${it.step.action.labelKo}", color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(sub, color = if (it.done) p.sub else p.accentText, fontSize = 12.sp, maxLines = 1)
        }
        Text(level, color = p.muted, fontSize = 11.sp, maxLines = 1)
        if (it.done && !it.guide) {
            GhostButton("확인됨", {}, height = 40.dp, fontSize = 13.sp, enabled = false)
        } else {
            GhostButton(if (it.guide) (if (it.done) "확인됨" else "했어요") else "다시 읽기", onAction, height = 40.dp, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SelectedSeatBox(ui: UiState, vm: MainViewModel, seat: SeatId) {
    val p = LocalPalette.current
    val fore = ui.vehicle.positions[seat to SeatAxis.FORE_AFT]
    val back = ui.vehicle.positions[seat to SeatAxis.BACKREST]
    val canMove = !ui.isDriving && ui.run?.finished != false
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.chip)
            .border(1.dp, p.chipBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            buildAnnotatedString {
                append("선택한 좌석 · ")
                withStyle(SpanStyle(color = p.accentText)) { append(seat.labelKo) }
                withStyle(SpanStyle(color = p.muted)) {
                    append("  앞뒤 ${fore?.toString() ?: "-"} · 등받이 ${back?.toString() ?: "-"}")
                    if (ui.vehicle.occupancy[seat] == true) append(" · 사람이 앉아 있어요")
                }
            },
            color = p.chipText, fontSize = 13.sp, maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val c = vm.controller
            val m = Modifier.weight(1f)
            GhostButton("앞으로", { c.nudgeSeat(seat, SeatAxis.FORE_AFT, MoveDirection.FORWARD) }, m, height = 40.dp, radius = 12.dp, fontSize = 13.sp, enabled = canMove)
            GhostButton("뒤로", { c.nudgeSeat(seat, SeatAxis.FORE_AFT, MoveDirection.BACKWARD) }, m, height = 40.dp, radius = 12.dp, fontSize = 13.sp, enabled = canMove)
            GhostButton("세우기", { c.nudgeSeat(seat, SeatAxis.BACKREST, MoveDirection.FORWARD) }, m, height = 40.dp, radius = 12.dp, fontSize = 13.sp, enabled = canMove)
            GhostButton("눕히기", { c.nudgeSeat(seat, SeatAxis.BACKREST, MoveDirection.BACKWARD) }, m, height = 40.dp, radius = 12.dp, fontSize = 13.sp, enabled = canMove)
            GhostButton("열선", { c.toggleSeatHeat(seat) }, m, height = 40.dp, radius = 12.dp, fontSize = 13.sp, enabled = canMove)
        }
    }
}

@Composable
private fun DashedChip(text: String) {
    val p = LocalPalette.current
    Box(
        Modifier
            .height(30.dp)
            .drawBehind {
                drawRoundRect(
                    p.warn.copy(alpha = 0.7f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))),
                )
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = p.warn, fontSize = 12.sp, maxLines = 1) }
}

private fun support(ui: UiState, step: SeatStep): Support {
    val feature = when (val a = step.action) {
        is SeatAction.Move -> if (a.axis == SeatAxis.BACKREST) SeatFeature.BACKREST_MOVE else SeatFeature.FORE_AFT_MOVE
        is SeatAction.Heat -> SeatFeature.HEAT
        is SeatAction.Vent -> SeatFeature.VENT
        else -> return Support.UNKNOWN
    }
    return ui.vehicle.support(step.seat, feature)
}
