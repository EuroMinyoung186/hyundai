package ai.moduui.jari.ui.screens

import ai.moduui.core.model.BabyState
import ai.moduui.core.model.StateSource
import ai.moduui.core.seat.Occupant
import ai.moduui.core.seat.SeatAction
import ai.moduui.core.seat.SeatAxis
import ai.moduui.core.seat.SeatStep
import ai.moduui.jari.BuildConfig
import ai.moduui.jari.control.StepOutcome
import ai.moduui.jari.control.UiState
import ai.moduui.jari.ui.MainViewModel
import ai.moduui.jari.ui.components.Chip
import ai.moduui.jari.ui.components.Ic
import ai.moduui.jari.ui.components.LineIcon
import ai.moduui.core.model.GeoPoint
import ai.moduui.jari.ui.components.MapStop
import ai.moduui.jari.ui.components.RouteMap
import ai.moduui.jari.ui.components.SingleSeat3D
import ai.moduui.jari.ui.components.TitleMont
import ai.moduui.jari.ui.components.designCard
import ai.moduui.jari.ui.hm
import ai.moduui.jari.navi.RoadAhead
import ai.moduui.jari.ui.theme.LocalPalette
import ai.moduui.jari.ui.theme.MapColors
import ai.moduui.jari.ui.theme.Montserrat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 주행 중 화면 (디자인 C-3 · C-4 · C-5 · C-6).
 * 왼쪽 큰 지도 위에 아이콘·짧은 글 칩만, 오른쪽 400 폭에 작은 지도와 큰 버튼.
 * 주행 중에는 긴 글 · 입력창 · 애니메이션 없음 (Pleos 운전 주의 분산 가이드).
 */

private enum class DriveMode { NORMAL, REST, CRY }

/**
 * 내비 옆(Pleos 멀티 윈도우 세로 창)에 띄우는 화면.
 * 디자인에서 지도 옆에 붙어 있던 것만: 출발 전 탑승 카드(1-A) · 아기 상태(C-3) · 그 자리(2-B) · 수유 휴게소(C-4) · 울음(C-5) · 도착(C-6).
 * 홈 · 3D 세팅 · 설정은 앱을 넓혔을 때만 (Pleos 가이드: 세로는 핵심 기능, 가로는 확장 콘텐츠).
 */
@Composable
fun NaviSideScreen(ui: UiState, vm: MainViewModel) {
    var arriveDismissed by rememberSaveable(ui.arrived) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(14.dp)) {
        when {
            ui.arrived && !ui.isDriving && !arriveDismissed -> ArriveScreen(ui, vm) { arriveDismissed = true }
            !ui.isDriving && !ui.departed -> BeforeDepartureSide(ui, vm)
            else -> DriveScreen(ui, vm)
        }
    }
}

/** 출발 전, 내비 옆: 목적지 정하기 · 휴대폰에서 받은 여정 · 좌석 준비 상태. 3D 는 앱을 넓혀서. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BeforeDepartureSide(ui: UiState, vm: MainViewModel) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SideStatusRow(ui)
        DestinationCard(ui, vm)
        ui.trip?.let { t ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tripChips(t).forEach { Chip(it) }
            }
        }
        RouteCard(ui)
        ui.plan?.let { plan ->
            Column(Modifier.fillMaxWidth().designCard(p, 24.dp).padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("세팅 준비됨", color = p.accentText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(plan.summaryKo, color = p.text, fontSize = 16.sp)
            }
        }
        Text("3D 좌석 상태 · 아기 정보는 가운데 ⋮ 를 끌어 앱을 넓히면 열려요", color = p.muted, fontSize = 13.sp)
    }
}

/** 내비 옆 창 맨 위: 기어 · 속도 + 시계 */
@Composable
private fun SideStatusRow(ui: UiState) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (ui.isDriving) "주행 중 ${ui.driving.gear ?: ""}${ui.driving.speedKmh?.let { " · ${it.toInt()} km/h" } ?: ""}"
            else "${ui.driving.gear ?: "P"} 정차 중",
            color = p.sub, fontSize = 16.sp, modifier = Modifier.weight(1f),
        )
        Text(ui.now.hm(), color = p.text, fontFamily = Montserrat, fontWeight = FontWeight.Light, fontSize = 34.sp)
    }
}

/**
 * 디자인 2-B "그 자리 3D". 파란 배경에 좌석 하나 + 왜 바뀌는지 + 값 + 되돌리기.
 * 좌석은 P단에서만 움직이므로 주행 중에는 "정차하면 맞춰요"로 보여 줌.
 */
@Composable
private fun SeatDetailPanel(ui: UiState, vm: MainViewModel, back: () -> Unit) {
    val plan = ui.plan ?: return
    val comfortSeats = ui.comfort.map { it.seat }.distinct()
    val seats = (comfortSeats + plan.sdkSteps.map { it.seat }).distinct()
    if (seats.isEmpty()) return
    // 방금 조절한 자리를 먼저 보여 줌
    var seat by remember(seats) { mutableStateOf(comfortSeats.firstOrNull() ?: plan.carSeatSeats.firstOrNull { it in seats } ?: seats.first()) }
    var locked by rememberSaveable { mutableStateOf(false) }
    val steps = plan.sdkSteps.filter { it.seat == seat }
    val comfortHere = ui.comfort.filter { it.seat == seat }
    val comfortBack = comfortHere.any { it.axis == SeatAxis.BACKREST && it.applied }
    fun outcomeOf(s: SeatStep) = ui.run?.steps?.firstOrNull { it.step == s }?.outcome
    fun done(s: SeatStep) = outcomeOf(s).let { it == StepOutcome.DONE || it == StepOutcome.SENT }
    // 등받이: 값이 클수록 곧게 → 40°..10° (세팅 화면 3D 와 같은 환산)
    val backStep = steps.firstOrNull { (it.action as? SeatAction.Move)?.axis == SeatAxis.BACKREST }
    val targetDeg = (backStep?.action as? SeatAction.Move)?.let { 40f - it.targetFraction * 30f }
    val baseDeg = 22f
    val currentDeg = ui.vehicle.positions[seat to SeatAxis.BACKREST]?.let { 40f - it * 0.3f }
        ?: when {
            comfortBack -> baseDeg + 6f // 소폭 조절은 그림으로만 표시 (실제 각도는 차가 알려 주지 않음)
            targetDeg != null && backStep != null && done(backStep) -> targetDeg
            else -> baseDeg
        }
    val ghostDeg = when {
        comfortBack -> baseDeg
        targetDeg == null -> null
        kotlin.math.abs(currentDeg - targetDeg) > 0.5f -> targetDeg
        else -> baseDeg
    }
    val applied = comfortHere.any { it.applied }
    val allDone = applied || (steps.isNotEmpty() && steps.all { done(it) })
    val seatColor = if (allDone) MapColors.mint else Color.White.copy(alpha = 0.85f)
    val seatFill = if (allDone) MapColors.mint.copy(alpha = 0.28f) else Color(0x2EE1E9FF)

    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.radialGradient(listOf(Color(0xFFA9B8F3), Color(0xFF5A72DA), Color(0xFF2F45B8)))),
    ) {
        SingleSeat3D(currentDeg, ghostDeg, seatColor, seatFill, animate = !locked && !ui.isDriving, modifier = Modifier.fillMaxSize())

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SeatIconButton(Ic.BACK, "돌아가기", selected = false, back)
                if (seats.size > 1) {
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(MapColors.overlay).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (s in seats) {
                            val on = s == seat
                            Box(
                                Modifier
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (on) MapColors.green else Color.Transparent)
                                    .clickable(role = Role.Tab) { seat = s }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(s.labelKo, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                        }
                    }
                }
                Box(Modifier.weight(1f))
                if (!ui.isDriving) SeatIconButton(if (locked) Ic.LOCK else Ic.REFRESH, if (locked) "회전 고정 풀기" else "회전 고정", locked) { locked = !locked }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MapColors.overlay).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(seat.labelKo, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    listOfNotNull(plan.assignments[seat]?.labelKo, if (applied) "조금 조절함" else if (allDone) "맞춤 완료" else "맞추기 전").joinToString(" · "),
                    color = if (allDone) MapColors.mint else MapColors.overlaySub, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
            // 디자인은 AI 표시지만 좌석 판단은 규칙 기반(SeatPlanner)이라 "이유"로 표기
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MapColors.overlay).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "이유",
                    color = Color(0xFF0B3B2A), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MapColors.mint).padding(horizontal = 7.dp, vertical = 2.dp),
                )
                Text(comfortHere.firstOrNull()?.reasonKo?.let { "$it · 작게만 조절해요" } ?: steps.firstOrNull()?.reasonKo ?: "", color = Color.White, fontSize = 15.sp)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in comfortHere) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MapColors.overlay).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(c.labelKo, color = MapColors.overlaySub, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(if (c.outcome == StepOutcome.DONE) "적용됨" else c.outcome?.labelKo ?: "곧 적용", color = MapColors.mint, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            for (s in steps) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MapColors.overlay).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.action.labelKo, color = MapColors.overlaySub, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    // 정차 때 자동으로 도는 건 예약된 단계뿐. 나머지는 앱을 넓혀 세팅 화면에서 맞춤.
                    Text(
                        outcomeOf(s)?.takeIf { it != StepOutcome.PENDING }?.labelKo
                            ?: if (s in ui.pendingSteps) "정차하면 맞춰요" else "세팅에서 맞춰요",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            // 소폭 조절은 주행 중에도 되돌릴 수 있고, 큰 이동은 정차해야 되돌림
            val undoComfort = ui.comfort.any { it.applied }
            val canUndo = undoComfort || (ui.run?.canUndo == true && !ui.isDriving)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MapColors.tileButton.copy(alpha = if (canUndo) 1f else 0.6f))
                    .clickable(enabled = canUndo, role = Role.Button) {
                        if (undoComfort) vm.controller.undoComfort() else vm.controller.runner.undoLast()
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                LineIcon(Ic.UNDO, Color.White.copy(alpha = if (canUndo) 1f else 0.5f), size = 22.dp, stroke = 2.4f)
                Text(
                    if (ui.isDriving && !undoComfort) "정차하면 되돌릴 수 있어요" else "되돌리기",
                    color = Color.White.copy(alpha = if (canUndo) 1f else 0.6f), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                )
            }
        }
    }
}

@Composable
private fun SeatIconButton(icon: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MapColors.green else MapColors.overlay)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { LineIcon(icon, Color.White, size = 24.dp, stroke = 2.2f) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriveScreen(ui: UiState, vm: MainViewModel) {
    val offer = ui.restStops
    val mode = when {
        offer == null -> DriveMode.NORMAL
        offer.nursing -> DriveMode.REST
        else -> DriveMode.CRY
    }
    val first = offer?.candidates?.firstOrNull()
    val messages = buildList {
        when (mode) {
            DriveMode.REST -> add(
                when {
                    offer == null -> ""
                    offer.addedName != null -> "🍼 경유지에 넣었어요 · ${offer.addedName}"
                    offer.candidates.isEmpty() -> "🍼 경로 근처에 수유실 있는 휴게소가 없어요"
                    else -> "🍼 ${feedLead(ui)} · 수유실 있는 휴게소 ${offer.candidates.size}곳"
                },
            )
            DriveMode.CRY -> add(
                first?.let { "뒤돌아보지 마세요 · ${"%.0f".format(it.distanceKm)}km 뒤 ${it.restArea.name}에서 쉬어 갈 수 있어요" }
                    ?: "뒤돌아보지 마세요 · 가까운 정차 지점을 찾지 못했어요",
            )
            DriveMode.NORMAL -> {
                if (ui.quiet && ui.simulation) add("조치됨 · 안내 음성 작게 · ${ui.profile.nickname} 자는 동안")
                if (ui.pendingSteps.isNotEmpty()) add("정차하면 좌석 ${ui.pendingSteps.size}단계를 맞춰요")
            }
        }
        ui.banner?.let { add(it) }
    }
    val hint = when (mode) {
        DriveMode.NORMAL -> null
        DriveMode.REST -> "오른쪽 번호를 누르세요 · 자세한 정보는 정차하면 보여요"
        DriveMode.CRY -> first?.let { "음성 안내 중 · “${"%.0f".format(it.distanceKm)}킬로미터 뒤 ${it.restArea.name}가 있어요”" }
    }

    // 실제 내비가 있으면 지도는 내비가 보여 줌 → 앱은 내비 옆(멀티 윈도우)에 맞는 패널만
    if (!ui.simulation) {
        // 디자인 2-B: 칩을 누르면 그 자리를 크게 (휴게소 제안 · 울음 안내가 먼저)
        var seatOpen by rememberSaveable { mutableStateOf(false) }
        val canOpenSeat = ui.plan?.sdkSteps?.isNotEmpty() == true || ui.comfort.isNotEmpty()
        if (seatOpen && canOpenSeat && mode == DriveMode.NORMAL) {
            SeatDetailPanel(ui, vm) { seatOpen = false }
            return
        }
        DrivePanel(
            ui = ui,
            title = "ON THE ROAD",
            top = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BabyChip(ui, onClick = if (canOpenSeat && mode == DriveMode.NORMAL) ({ seatOpen = true }) else null)
                    FeedChip(ui)
                }
                RoadAdviceChip(ui)
                RouteCard(ui)
                RoadAheadCard(ui)
                DriveMapCard(ui)
                DriveStatusChips(ui, vm)
                messages.forEachIndexed { i, m ->
                    MessageChip(m, onClose = if (m == ui.banner) ({ vm.controller.clearBanner() }) else null, big = i == 0 && mode != DriveMode.NORMAL)
                }
            },
            bottom = {
                when (mode) {
                    DriveMode.NORMAL -> BabyButtons(ui, vm, Modifier)
                    DriveMode.REST -> {
                        RestList(ui)
                        RestButtons(ui, vm, Modifier)
                    }
                    DriveMode.CRY -> CryCard(ui, vm, Modifier.fillMaxWidth(), fill = false)
                }
            },
        )
        return
    }

    MapLayout(
        ui = ui,
        bottomHint = hint ?: ui.navi.route?.let { "${routeText(it.remainSeconds, it.remainMeters)} · 주행 중에는 아이콘과 음성만" },
        main = {
            Column(Modifier.align(Alignment.TopStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BabyChip(ui)
                    FeedChip(ui)
                }
                RoadAdviceChip(ui)
                Box(Modifier.widthIn(max = 520.dp)) { RoadAheadCard(ui) }
                messages.forEachIndexed { i, m ->
                    MessageChip(m, onClose = if (m == ui.banner) ({ vm.controller.clearBanner() }) else null, big = i == 0 && mode != DriveMode.NORMAL)
                }
            }
            ClockChip(ui, Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp))
        },
        aside = {
            when (mode) {
                DriveMode.NORMAL -> BabyButtons(ui, vm, Modifier.align(Alignment.BottomCenter))
                DriveMode.REST -> RestButtons(ui, vm, Modifier.align(Alignment.BottomCenter))
                DriveMode.CRY -> CryCard(ui, vm, Modifier.fillMaxSize())
            }
        },
    )
}

/** 디자인 C-6 도착 · 하차 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArriveScreen(ui: UiState, vm: MainViewModel, home: () -> Unit) {
    val asleep = ui.baby == BabyState.ASLEEP
    val dest = ui.trip?.destinationName ?: ui.navi.route?.destinationName
    if (!ui.simulation) {
        DrivePanel(
            ui = ui,
            title = "ARRIVED",
            top = {
                if (ui.rearSeatAlert) {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFB91C1C)).padding(horizontal = 18.dp, vertical = 16.dp),
                    ) { Text("⚠ 문을 잠갔어요 · 카시트에 아기가 있어요", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BabyChip(ui, sub = if (asleep) "도착했어요 · 깨우지 않게" else null)
                    FeedChip(ui)
                }
                MessageChip("${dest ?: "목적지"} 도착 · 내리기 전에 뒷좌석 아기를 꼭 확인하세요", big = true)
                if (BuildConfig.DEBUG) ui.lastVoice?.let { MessageChip("🔊 $it") }
            },
            bottom = { ArriveCard(ui, vm, home, Modifier.fillMaxWidth(), fill = false) },
        )
        return
    }
    MapLayout(
        ui = ui,
        bottomLeft = "${dest ?: "목적지"} 도착",
        bottomHint = "내리기 전에 뒷좌석 아기를 꼭 확인하세요",
        main = {
            Column(Modifier.align(Alignment.TopStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    BabyChip(ui, sub = if (asleep) "도착했어요 · 깨우지 않게" else null)
                    FeedChip(ui)
                }
                MessageChip("${ui.driving.gear ?: "P"} 정차 · ${if (asleep) "잠든 아기 하차 준비" else "하차 준비"}", big = true)
            }
            ClockChip(ui, Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp))
        },
        aside = { ArriveCard(ui, vm, home, Modifier.fillMaxSize(), fill = true) },
    )
}

@Composable
private fun ArriveCard(ui: UiState, vm: MainViewModel, home: () -> Unit, modifier: Modifier, fill: Boolean) {
    val asleep = ui.baby == BabyState.ASLEEP
    AsideCard(modifier) {
        Text(if (asleep) "조용히 내릴 수 있게 준비했어요" else "내리기 좋게 준비했어요", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 출발 준비 때의 단계가 아니라 하차 준비 단계만
            ui.run?.takeIf { it.reasonKo == "편한 하차" }?.steps?.forEach { s ->
                val ok = s.outcome == StepOutcome.DONE || s.outcome == StepOutcome.SENT
                CheckLine("${if (ok) "✓" else "·"} ${s.step.seat.labelKo} ${s.step.action.labelKo} · ${s.outcome.labelKo}")
            }
            ui.comfort.filter { it.applied }.forEach { CheckLine("✓ ${it.labelKo}") }
            if (ui.quiet) CheckLine("✓ 안내 음성 작게 · 아기가 자고 있어요")
            CheckLine("✓ 내리기 전에 뒷좌석 아기 확인 안내")
        }
        ui.run?.countdown?.let { c ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${c}초 뒤 좌석이 움직여요", color = MapColors.awake, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OverlayButton("멈추기", { vm.controller.runner.cancel() }, Modifier.width(110.dp), height = 52.dp)
            }
        }
        if (fill) Box(Modifier.weight(1f))
        Text("모두 내린 뒤 평소 위치로 돌려요", color = Color(0xFF9CA3AF), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OverlayButton(
                "원래대로",
                { vm.controller.restoreSeats() },
                Modifier.weight(1.4f),
                primary = true,
                enabled = (ui.run?.canUndo == true || ui.comfort.any { it.applied }) && !ui.isDriving,
            )
            OverlayButton("그대로 두기", {
                vm.controller.clearFocus()
                home()
            }, Modifier.weight(1f))
        }
    }
}

/**
 * 기본 내비 옆(Pleos 멀티 윈도우)에 띄우는 패널. 지도와 길 안내는 내비가 하므로 앱은 아기 · 수유 · 정차 · 좌석만.
 * 창이 넓으면(앱 하나만 열림) 두 열, 좁으면(내비와 나란히) 한 열.
 */
@Composable
private fun DrivePanel(
    ui: UiState,
    title: String,
    top: @Composable ColumnScope.() -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 1100.dp
        val header: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    TitleMont(title, 30.sp)
                    Text(
                        if (ui.isDriving) "주행 중 ${ui.driving.gear ?: ""}${ui.driving.speedKmh?.let { " · ${it.toInt()} km/h" } ?: ""}"
                        else "${ui.driving.gear ?: "P"} 정차 중",
                        color = p.sub, fontSize = 15.sp,
                    )
                }
                Text(ui.now.hm(), color = p.text, fontFamily = Montserrat, fontWeight = FontWeight.Light, fontSize = 34.sp)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    header()
                    // 주행 중에는 내비 옆 좁은 창이 기본. 앱이 창을 나눌 공개 API 가 없어 한 번은 직접 나눠야 함 (시스템이 기억함)
                    if (ui.isDriving) SplitHint()
                    top()
                    if (!ui.isDriving) Text("화면 오른쪽 끝 ⋮ 를 끌면 내비와 나란히 볼 수 있어요", color = p.muted, fontSize = 13.sp)
                }
                Column(Modifier.width(520.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) { bottom() }
            }
        } else {
            // 내비 옆 좁은 창: 제목 없이 디자인 C-3 처럼 칩부터
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SideStatusRow(ui)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { top() }
                bottom()
            }
        }
    }
}

/** 주행 중 넓은 창일 때: 내비 옆으로 옮기는 법 (한 번 나누면 다음부터 아이콘을 눌러도 내비 옆에 뜸) */
@Composable
private fun SplitHint() {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.chip)
            .border(1.dp, p.accentRing, RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("⋮", color = p.accentText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("화면 오른쪽 끝 ⋮ 를 왼쪽으로 끌어 내비 옆에 두세요", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text("한 번 나누면 다음부터는 아이콘만 눌러도 내비 옆에 열려요", color = p.sub, fontSize = 15.sp)
        }
    }
}

/** 남은 시간 · 도착 · 아기와 함께 도착 예상 (내비 남은 시간 + 수유 · 기저귀 정차) */
@Composable
private fun RouteCard(ui: UiState) {
    val p = LocalPalette.current
    val r = ui.navi.route
    val eta = ui.eta
    Column(Modifier.fillMaxWidth().designCard(p, 24.dp).padding(horizontal = 22.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (r == null) {
            Text("경로 안내 없음", color = p.sub, fontSize = 14.sp)
            Text("내비에서 목적지를 정하면 남은 시간과 아기 정차 계획을 보여 줘요", color = p.text, fontSize = 17.sp)
        } else {
            Text("${r.destinationName ?: "목적지"}까지", color = p.sub, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(durationKo((r.remainSeconds / 60).toInt()), color = p.text, fontSize = 30.sp, fontWeight = FontWeight.Medium, modifier = Modifier.alignByBaseline())
                Text("내비 도착 ${ui.now.plusSeconds(r.remainSeconds).hm()}", color = p.sub, fontSize = 15.sp, modifier = Modifier.alignByBaseline())
            }
            if (eta != null) {
                Text(
                    "아기와 함께 도착 ${eta.predictedArrival.hm()} · +${eta.extraMinutes}분 (수유 · 기저귀 정차 포함)",
                    color = p.accentText, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                )
            }
            // 경유지(수유 휴게소 등)까지 남은 거리 · 시간은 내비 알림(onDrivingInfo)에서
            val next = RoadAhead.nextWaypoint(ui.navi)
            when {
                next != null -> Text(
                    "경유 · ${next.name} ${RoadAhead.distanceKo(next.meters)} · ${RoadAhead.durationKo(next.seconds)} 뒤",
                    color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                )
                ui.navi.waypoints.isNotEmpty() -> Text("경유 · ${ui.navi.waypoints.joinToString()}", color = p.sub, fontSize = 14.sp)
            }
        }
    }
}

/** 앞길 안내 (아기 관점): 아기가 자는데 과속방지턱 · 급회전이 가까우면 크게. 규칙은 RoadAhead */
@Composable
private fun RoadAdviceChip(ui: UiState) {
    val a = RoadAhead.advice(ui.navi, ui.baby == BabyState.ASLEEP, ui.profile.nickname) ?: return
    if (!a.urgent) {
        MessageChip("⚠ ${a.text}")
        return
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFB45309)).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🌙", fontSize = 24.sp)
        Text(a.text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/**
 * 내비 알림으로 받은 다음 회전 · 제한 속도 · 앞쪽 주의 지점 (NaviHelper onTBTInfo · onCurrentRoadSpeedLimit · onCautionPointInfo).
 * 내비 옆에서는 내비가 크게 보여 주므로 한 줄 카드로 작게.
 */
@Composable
private fun RoadAheadCard(ui: UiState) {
    val p = LocalPalette.current
    val n = ui.navi
    val turn = n.nextTurn
    val limit = n.camera?.speedLimit?.takeIf { it > 0 && (n.camera?.meters ?: Int.MAX_VALUE) <= 1000 } ?: n.speedLimit
    val caution = n.cautions.firstOrNull { RoadAhead.cautionKo(it.type) != null && it.meters in 0..3000 }
    if (turn == null && limit == null && caution == null) return
    Row(
        Modifier.fillMaxWidth().designCard(p, 24.dp).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (turn != null) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Brush.verticalGradient(listOf(p.accent, p.accentDark))),
                contentAlignment = Alignment.Center,
            ) { Text(RoadAhead.turnArrow(turn.type), color = p.onAccent, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (turn != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(RoadAhead.distanceKo(turn.meters), color = p.text, fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.alignByBaseline())
                    Text(RoadAhead.turnKo(turn.type), color = p.sub, fontSize = 15.sp, modifier = Modifier.alignByBaseline())
                }
                if (turn.description.isNotBlank()) Text(turn.description, color = p.sub, fontSize = 14.sp, maxLines = 1)
            }
            if (caution != null) {
                Text(
                    "주의 · ${RoadAhead.cautionKo(caution.type)} ${RoadAhead.distanceKo(caution.meters)} 앞",
                    color = p.warn, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                )
            }
        }
        if (limit != null) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Color.White).border(5.dp, Color(0xFFDC2626), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("$limit", color = Color(0xFF111827), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold) }
        }
    }
}

/** 주행 중 상태 칩: 음성 자막(시연) · 조치 · 수유 미룸 · 속도 제안 */
@Composable
private fun DriveStatusChips(ui: UiState, vm: MainViewModel) {
    // TTS 엔진이 없는 에뮬레이터 시연에서 음성 안내를 글로 보여 줌 (출시 빌드에는 없음)
    if (BuildConfig.DEBUG) ui.lastVoice?.let { MessageChip("🔊 $it") }
    CareChip(ui, vm)
    MusicChip(ui, vm)
    ComfortChip(ui, vm)
    val asleep = ui.baby == BabyState.ASLEEP
    // 앱은 차 속도를 바꿀 수 없음 (SDK 는 크루즈 값 읽기만) → 운전자에게 제안만
    if (asleep && ui.isDriving && (ui.driving.speedKmh ?: 0f) >= SLEEP_SPEED_HINT_KMH) MessageChip("🌙 아기가 자요 · 속도를 조금 줄이면 더 편해요")
}

private const val SLEEP_SPEED_HINT_KMH = 90f

/** 카메라가 본 잠듦 · 깸 → [적용] (내비 위 알림과 같은 내용. 앱이 보일 때 여기서도 누를 수 있게) */
@Composable
private fun CareChip(ui: UiState, vm: MainViewModel) {
    val care = ui.careSuggestion ?: return
    val asleep = care == BabyState.ASLEEP
    val nick = ui.profile.nickname.ifBlank { "아기" }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MapColors.overlay)
            .border(1.dp, MapColors.mint.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (asleep) "📷 $nick 잠들었어요" else "📷 $nick 깼어요", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            if (asleep) "적용하면 안내 음성을 작게 하고 자장가를 작게 틀어요" else "적용하면 안내 음성을 원래대로 하고 동요를 틀어요",
            color = MapColors.overlaySub, fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayButton("적용", { vm.controller.applyCare() }, Modifier.weight(1f), primary = true, height = 56.dp)
            OverlayButton("괜찮아요", { vm.controller.dismissCare() }, Modifier.weight(1f), height = 56.dp)
        }
    }
}

/** 지금 트는 노래 + [끄기] */
@Composable
private fun MusicChip(ui: UiState, vm: MainViewModel) {
    val song = ui.music ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MapColors.overlaySoft).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🎵 ${song.labelKo} 트는 중", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        OverlayButton("끄기", { vm.controller.stopMusic() }, Modifier.width(110.dp), height = 48.dp, fontSize = 16.sp)
    }
}

/** 디자인 C-3 "조치됨 · … [되돌리기]". 움직이기 전이면 몇 초 뒤인지 알리고 [취소]. */
@Composable
private fun ComfortChip(ui: UiState, vm: MainViewModel) {
    val countdown = ui.comfortCountdown
    val pending = ui.comfort.filter { it.outcome == null }.joinToString(" · ") { it.labelKo }
    val done = ui.comfort.filter { it.outcome != null }.map {
        when {
            it.simulated -> "${it.labelKo} (시연)"
            it.applied -> it.labelKo
            else -> "${it.labelKo} 못 함 (${it.outcome?.labelKo})"
        }
    }
    val parts = listOfNotNull("안내 음성 작게".takeIf { ui.quiet }) + done
    if (parts.isEmpty() && countdown == null) return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MapColors.overlaySoft).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (countdown != null) Text("${countdown}초 뒤 $pending", color = MapColors.awake, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (parts.isNotEmpty()) Text("조치됨 · ${parts.joinToString(" · ")}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (ui.comfort.any { it.simulated }) Text("시연 값이에요 · 차량 서비스는 P단이 아니면 좌석 명령을 거부해요", color = MapColors.overlaySub, fontSize = 13.sp)
        }
        OverlayButton(if (countdown != null) "취소" else "↩ 되돌리기", { vm.controller.undoComfort() }, Modifier.width(130.dp), height = 52.dp, fontSize = 16.sp)
    }
}

/** 지도 없이 보여 주는 휴게소 후보 (C-4 의 번호 표시를 목록으로) */
@Composable
private fun RestList(ui: UiState) {
    val offer = ui.restStops ?: return
    AsideCard(Modifier.fillMaxWidth()) {
        Text(offer.reasonKo, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        if (offer.candidates.isEmpty()) CheckLine("경로 근처에서 찾지 못했어요")
        offer.candidates.take(2).forEachIndexed { i, cand -> StopRow(i, cand.restArea.name, cand.restArea.hasNursingRoom, cand.distanceKm, cand.etaMinutes) }
        offer.addedName?.let { CheckLine("✓ 내비 경유지에 넣었어요 · $it") }
    }
}

@Composable
private fun StopRow(i: Int, name: String, nursing: Boolean, km: Double, eta: Int) {
    val first = i == 0
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (first) MapColors.green else Color.White.copy(alpha = 0.08f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Text("${i + 1}", color = if (first) MapColors.green else Color(0xFF1E2430), fontWeight = FontWeight.ExtraBold)
        }
        Column(Modifier.weight(1f)) {
            Text(name + if (nursing) " · 수유실" else "", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text("${"%.0f".format(km)}km · 약 ${eta}분", color = if (first) MapColors.mintText else MapColors.overlaySub, fontSize = 13.sp)
        }
    }
}

// ───────────────────────────── 틀 ─────────────────────────────

/** 왼쪽 큰 지도 + 오른쪽 400 폭 패널. 창이 좁으면 위아래로. */
@Composable
private fun MapLayout(
    ui: UiState,
    main: @Composable BoxScope.() -> Unit,
    aside: @Composable BoxScope.() -> Unit,
    bottomLeft: String? = null,
    bottomHint: String? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp))) {
        val wide = maxWidth >= 900.dp
        val mainArea: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                DriveMap(ui, Modifier.matchParentSize(), withStops = false)
                main()
                RouteBar(ui, Modifier.align(Alignment.BottomCenter), left = bottomLeft, hint = bottomHint)
            }
        }
        val asideArea: @Composable (Modifier) -> Unit = { m ->
            Box(m) {
                // 작은 지도: 휴게소 후보 번호까지 (디자인 C-4)
                DriveMap(ui, Modifier.matchParentSize())
                Box(Modifier.fillMaxSize().padding(12.dp)) { aside() }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                mainArea(Modifier.weight(1f).fillMaxHeight())
                asideArea(Modifier.width(400.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                mainArea(Modifier.weight(1f).fillMaxWidth())
                asideArea(Modifier.height(440.dp).fillMaxWidth())
            }
        }
    }
}

// ───────────────────────────── 지도 위 칩 ─────────────────────────────

@Composable
private fun BabyChip(ui: UiState, sub: String? = null, onClick: (() -> Unit)? = null) {
    val nick = ui.profile.nickname
    val (emoji, color, title) = when (ui.baby) {
        BabyState.ASLEEP -> Triple("🌙", MapColors.sleep, "$nick 자는 중")
        BabyState.AWAKE, BabyState.DROWSY -> Triple("👀", MapColors.awake, "$nick 깼어요")
        BabyState.CRYING -> Triple("😢", MapColors.cry, "$nick 울어요")
        BabyState.HUNGRY -> Triple("🍼", MapColors.cry, "$nick 배고파해요")
        BabyState.UNKNOWN -> Triple("👶", Color(0xFFD1D5DB), "$nick 상태 모름")
    }
    val since = ui.babyMinutes
    val detail = sub ?: listOfNotNull(
        since?.let { if (it < 1) "방금" else "${it}분째" },
        if (ui.babySince != null) sourceLabel(ui.babySource) else null,
    ).joinToString(" · ").ifEmpty { "오른쪽 버튼이나 Gleo 로 알려 주세요" }
    Row(
        Modifier
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MapColors.overlay)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 8.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 24.sp)
        }
        Column {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(detail, color = MapColors.overlaySub, fontSize = 13.sp, maxLines = 1)
        }
    }
}

private fun sourceLabel(s: StateSource) = when (s) {
    StateSource.BABY_MONITOR -> "카메라 감지"
    StateSource.GLEO_VOICE -> "Gleo 음성"
    StateSource.BUTTON -> "보호자 버튼"
    StateSource.SIMULATION -> "시뮬레이션"
    StateSource.PHONE -> "보호자 휴대폰"
}

private fun feedLead(ui: UiState): String {
    val m = ui.minutesToFeed
    return when {
        m == null -> "수유 시간이 가까워요"
        m > 0 -> "수유 ${m}분 전이에요"
        else -> "수유 시간이에요"
    }
}

@Composable
private fun FeedChip(ui: UiState) {
    val m = ui.minutesToFeed
    val text = when {
        m == null -> "🍼 수유 기록 없음"
        m > 0 -> "🍼 다음 수유 ${durationKo(m)}"
        else -> "🍼 수유 시간 ${durationKo(-m)} 지남"
    }
    Box(
        Modifier.height(64.dp).clip(RoundedCornerShape(32.dp)).background(MapColors.overlay).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (m != null && m <= 20) MapColors.awake else Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun MessageChip(text: String, onClose: (() -> Unit)? = null, big: Boolean = false) {
    Row(
        Modifier
            .height(if (big) 56.dp else 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MapColors.overlaySoft)
            .then(if (onClose != null) Modifier.clickable(role = Role.Button, onClick = onClose) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, color = Color.White, fontSize = if (big) 17.sp else 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (onClose != null) Text("닫기", color = MapColors.mint, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ClockChip(ui: UiState, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(MapColors.overlaySoft).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(ui.now.hm(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

// ───────────────────────────── 오른쪽 패널 ─────────────────────────────

@Composable
private fun AsideCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .shadow(12.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(MapColors.overlayCard)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun CheckLine(text: String) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(text, color = Color(0xFFF3F4F6), fontSize = 15.sp, maxLines = 2)
    }
}

/** 지도 위 큰 버튼 (초록 = 주 동작) */
@Composable
private fun OverlayButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean = false,
    height: Dp = 64.dp,
    fontSize: TextUnit = if (primary) 19.sp else 17.sp,
) {
    val bg = when {
        primary || selected -> MapColors.green
        else -> Color.White.copy(alpha = 0.10f)
    }
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.04f))
            .then(if (selected) Modifier.border(2.dp, MapColors.mint, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            fontSize = fontSize,
            fontWeight = if (primary) FontWeight.ExtraBold else FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/** 디자인 C-3 오른쪽 아래 "아기 상태 아이콘" — 누르면 바로 알려 줌 */
@Composable
private fun BabyButtons(ui: UiState, vm: MainViewModel, modifier: Modifier) {
    val c = vm.controller
    AsideCard(modifier.fillMaxWidth()) {
        Text("아기 상태 알려 주기 · Gleo 에게 말해도 돼요", color = Color(0xFF9CA3AF), fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OverlayButton("🌙 잠듦", { c.reportBaby(BabyState.ASLEEP, StateSource.BUTTON) }, Modifier.weight(1f), selected = ui.baby == BabyState.ASLEEP)
            OverlayButton("👀 깸", { c.reportBaby(BabyState.AWAKE, StateSource.BUTTON) }, Modifier.weight(1f), selected = ui.baby == BabyState.AWAKE)
            OverlayButton("😢 울음", { c.reportBaby(BabyState.CRYING, StateSource.BUTTON) }, Modifier.weight(1f), selected = ui.baby == BabyState.CRYING)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OverlayButton("🍼 수유했어요", { c.logFeed() }, Modifier.weight(1f))
            OverlayButton("수유 휴게소", { c.requestRestStops(nursing = true) }, Modifier.weight(1f))
        }
    }
}

// ───────────────────────────── 지도 ─────────────────────────────

/**
 * 주행 지도: 현재 위치 · 목적지 · 들르기로 한 휴게소, withStops 면 휴게소 후보 번호(디자인 C-4)까지.
 * 위치는 NaviHelper 에서 받은 값. 에뮬레이터 내비는 지도를 그리지 않으므로 앱이 그림.
 */
@Composable
private fun DriveMap(ui: UiState, modifier: Modifier, withStops: Boolean = true) {
    val offer = ui.restStops
    val added = offer?.addedName?.let { name -> offer.candidates.firstOrNull { it.restArea.name == name } }
    val stops = when {
        !withStops || offer == null -> emptyList()
        added != null -> listOf(MapStop(1, "${added.restArea.name.removeSuffix("휴게소")} · 경유", added.restArea.location, primary = true))
        else -> offer.candidates.take(2).mapIndexed { i, c ->
            MapStop(
                number = i + 1,
                label = "${c.restArea.name.removeSuffix("휴게소")} · ${"%.0f".format(c.distanceKm)}km${if (c.restArea.hasNursingRoom) " · 🍼" else ""}",
                point = c.restArea.location,
                primary = i == 0,
            )
        }
    }
    RouteMap(
        here = ui.navi.current,
        destination = ui.navi.route?.destination,
        destinationName = ui.navi.route?.destinationName ?: ui.trip?.destinationName,
        via = listOfNotNull<GeoPoint>(added?.restArea?.location),
        stops = stops,
        path = ui.roadPath,
        done = ui.roadDone,
        modifier = modifier,
    )
}

/** 내비 옆 · 앱 전체 주행 패널의 지도 카드 */
@Composable
private fun DriveMapCard(ui: UiState) {
    Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(24.dp))) {
        DriveMap(ui, Modifier.matchParentSize())
    }
}

@Composable
private fun RestButtons(ui: UiState, vm: MainViewModel, modifier: Modifier) {
    val offer = ui.restStops ?: return
    val n = offer.candidates.size
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            offer.addedName != null -> OverlayButton("주행 화면으로", { vm.controller.showDrive() }, Modifier.weight(1f), primary = true)
            else -> {
                if (n >= 1) OverlayButton("1 경유하기", { vm.addRestStop(0) }, Modifier.weight(1.6f), primary = true)
                if (n >= 2) OverlayButton("2", { vm.addRestStop(1) }, Modifier.weight(1f).background(MapColors.overlay, RoundedCornerShape(14.dp)))
                OverlayButton("괜찮아요", { vm.controller.dismissRestStops() }, Modifier.weight(1.3f).background(MapColors.overlay, RoundedCornerShape(14.dp)))
            }
        }
    }
}

/** 디자인 C-5 아기 울음: 가까운 정차 지점 */
@Composable
private fun CryCard(ui: UiState, vm: MainViewModel, modifier: Modifier, fill: Boolean = true) {
    val offer = ui.restStops ?: return
    AsideCard(modifier) {
        Text("가까운 정차 지점", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        offer.candidates.take(2).forEachIndexed { i, cand -> StopRow(i, cand.restArea.name, cand.restArea.hasNursingRoom, cand.distanceKm, cand.etaMinutes) }
        if (offer.candidates.isEmpty()) CheckLine("경로 근처에서 찾지 못했어요")
        offer.addedName?.let { CheckLine("✓ 경유지에 넣었어요 · $it") }
        // 디자인 C-5: 동승 보호자가 있으면 자리 조절 제안
        val hasCaregiver = ui.plan?.assignments?.values?.contains(Occupant.CAREGIVER) == true
        val move = ui.comfort.firstOrNull { it.axis == SeatAxis.FORE_AFT }
        if (hasCaregiver && move != null) {
            CheckLine(
                when {
                    move.simulated -> "✓ 보호자 자리를 아기 쪽으로 옮겼어요 (시연)"
                    move.applied -> "✓ 보호자 자리를 아기 쪽으로 옮겼어요"
                    move.outcome != null -> "· 보호자 자리를 옮기지 못했어요 (${move.outcome.labelKo})"
                    else -> "· 곧 보호자 자리를 옮겨요"
                },
            )
        } else if (hasCaregiver) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("동승 보호자 자리를 아기 쪽으로 조금 옮길까요?", color = Color(0xFFE5E7EB), fontSize = 14.sp, modifier = Modifier.weight(1f))
                OverlayButton("옮기기", { vm.controller.moveCaregiverTowardBaby() }, Modifier.width(100.dp), height = 48.dp, fontSize = 15.sp)
            }
        }
        if (fill) Box(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (offer.addedName == null && offer.candidates.isNotEmpty()) {
                OverlayButton("1 들르기", { vm.addRestStop(0) }, Modifier.weight(1.4f), primary = true)
            }
            OverlayButton(if (offer.addedName == null) "계속 가기" else "주행 화면으로", { vm.controller.showDrive() }, Modifier.weight(1f))
        }
    }
}
