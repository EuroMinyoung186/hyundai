package ai.moduui.jari.ui.screens

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.mutableStateMapOf
import ai.moduui.jari.ui.components.Stepper
import ai.moduui.jari.ui.components.SettingRow
import ai.moduui.jari.ui.components.backrestDeg
import ai.moduui.jari.ui.components.toPose
import ai.moduui.jari.ui.components.D3
import ai.moduui.jari.ui.components.LuggageColor
import ai.moduui.jari.ui.components.TrunkLoad
import ai.moduui.jari.ui.components.SeatBox3D
import ai.moduui.core.seat.TrunkEstimate
import ai.moduui.jari.control.trunkEstimate
import ai.moduui.jari.control.seatViews
import ai.moduui.jari.control.SeatView
import ai.moduui.jari.navi.NaviPlace
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
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
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.compose.foundation.Image
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

// ───────────────────────────── 홈: 3D 좌석 · 차와 연동 ─────────────────────────────

/**
 * 차 앱 홈. 휴대폰 3D 시뮬과 같은 모양으로 지금 좌석 · 트렁크 상태를 보여 주고(차 SDK 값),
 * 좌석을 누르면 그 자리를 직접 맞춤(휴대폰 seatAdjust 와 같은 길: SeatStepRunner).
 * 오른쪽은 목적지 · 진행 상황 · 트렁크 · 이번 여정의 좌석 단계.
 */
@Composable
fun HomeScreen(ui: UiState, vm: MainViewModel) {
    val plan = ui.plan
    val seats = seatViews(ui)
    val trunk = trunkEstimate(ui)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // 고르는 동안 3D 에 바로 보이게 (보내기 전 값)
    val foreEdit = remember { mutableStateMapOf<String, Int>() }
    val backEdit = remember { mutableStateMapOf<String, Int>() }
    val guideDone = remember(plan) { mutableStateListOf<Int>() }
    var step by remember(plan) { mutableIntStateOf(0) }
    val items = plan?.let { boardItems(ui, it, guideDone) }.orEmpty()
    val pick: (String) -> Unit = { id -> selected = if (selected == id) null else id }

    val card: @Composable (Modifier) -> Unit = { m ->
        Seat3DCard(ui, seats, trunk, selected, foreEdit, backEdit, pick, m)
    }
    val side: @Composable ColumnScope.() -> Unit = {
        DestinationCard(ui, vm)
        if (plan == null) RunCard(ui, vm)
        seats.firstOrNull { it.id.name == selected }?.let { s ->
            SeatAdjustCard(
                ui, s,
                foreAft = foreEdit[s.id.name] ?: s.foreAft ?: 50,
                backrest = backEdit[s.id.name] ?: s.backrest ?: 75,
                onFore = { foreEdit[s.id.name] = it },
                onBack = { backEdit[s.id.name] = it },
                onSend = {
                    vm.controller.adjustSeat(
                        s.id,
                        (foreEdit[s.id.name] ?: s.foreAft)?.takeIf { s.foreAftMove },
                        (backEdit[s.id.name] ?: s.backrest)?.takeIf { s.backrestMove },
                        "차 화면",
                    )
                },
                onClose = {
                    foreEdit.remove(s.id.name)
                    backEdit.remove(s.id.name)
                    selected = null
                },
            )
        }
        TrunkCard(trunk)
        if (plan != null) {
            BoardingChecklist(ui, vm, plan, items, guideDone, step, { step = it }, Modifier.fillMaxWidth().height(560.dp))
        } else {
            WaitingCard(ui, Modifier.fillMaxWidth())
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        if (w >= 1100.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                card(Modifier.weight(1f).fillMaxHeight())
                Column(
                    Modifier.width(minOf(580.dp, w * 0.42f)).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = side,
                )
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                card(Modifier.fillMaxWidth().height(560.dp))
                side()
            }
        }
    }
}

/** 휴대폰 3D 시뮬과 같은 어두운 카드: 좌석 · 트렁크 3D, 범례, 좌석별 지금 값 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Seat3DCard(
    ui: UiState,
    seats: List<SeatView>,
    trunk: TrunkEstimate,
    selected: String?,
    foreEdit: Map<String, Int>,
    backEdit: Map<String, Int>,
    onSeatTap: (String) -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    val folded = trunk.folded.map { it.name }.toSet()
    val poses = seats.map { s -> s.toPose(foreEdit[s.id.name], backEdit[s.id.name], folded = s.id.name in folded) }
    val lug = ui.trip?.luggage.orEmpty()
    fun n(vararg t: LuggageType) = t.sumOf { lug[it] ?: 0 }
    val load = TrunkLoad(
        strollers = n(LuggageType.STROLLER, LuggageType.COMPACT_STROLLER),
        suitcases = n(LuggageType.SUITCASE, LuggageType.CARRY_ON),
        babyBags = n(LuggageType.BABY_BAG, LuggageType.PORTABLE_CRIB),
        liters = trunk.liters,
    )
    Column(
        // 낮 모드는 다른 카드처럼 흰 카드 + 그림자, 밤 모드는 휴대폰과 같은 어두운 카드
        modifier.designCard(LocalPalette.current, 28.dp).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TitleMont("3D SEATS", 30.sp, color = D3.text)
                Text(
                    "${if (ui.isDriving) "주행 중" else "${ui.driving.gear ?: "P"} 정차 중"} · 차 SDK 좌석 값 그대로 · 좌석을 누르면 직접 맞춰요",
                    color = D3.sub, fontSize = 14.sp, maxLines = 2,
                )
            }
            PhonePill(ui.phone, Modifier)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SeatBox3D(poses, Modifier.fillMaxSize(), load, selected = selected, onSeatTap = onSeatTap)
            Text("▲ 차 앞", color = D3.sub, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopEnd))
            if (ui.simulation) Text("시뮬레이션", color = D3.warn, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopStart))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Legend3D(D3.accent, "전동 좌석")
            Legend3D(D3.warn, "앉아 있음 · 넘친 짐")
            Legend3D(D3.manual, "수동 · 모름")
            Legend3D(LuggageColor.trunk, "트렁크")
            Legend3D(LuggageColor.stroller, "유모차")
            Legend3D(LuggageColor.suitcase, "캐리어")
            Legend3D(LuggageColor.bag, "아기 짐가방")
        }
        // 좌석별 지금 값 (누르면 그 좌석 고르기)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in seats.sortedWith(compareBy({ it.id.row }, { it.id.side.name }))) {
                val sel = s.id.name == selected
                val chip = RoundedCornerShape(14.dp)
                Column(
                    Modifier
                        .clip(chip)
                        .background(if (sel) D3.chipSelected else D3.chip)
                        .border(1.dp, if (sel) D3.accent else D3.line, chip)
                        .clickable(role = Role.Button, onClickLabel = "${s.id.labelKo} 고르기") { onSeatTap(s.id.name) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(s.id.labelKo, color = D3.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        listOfNotNull(
                            s.foreAft?.let { "앞뒤 $it%" },
                            s.backrest?.let { "등받이 ${backrestDeg(it)}°" },
                            if (!s.movable) "수동" else null,
                            if (s.occupied == true) "앉음" else null,
                        ).joinToString(" · ").ifEmpty { "위치 모름" },
                        color = if (s.occupied == true) D3.warn else D3.sub, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Legend3D(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, color = D3.sub, fontSize = 13.sp)
    }
}

/**
 * 고른 좌석 직접 맞추기. 값은 3D 에 먼저 보이고, [맞추기]를 누르면 5초 뒤 움직임.
 * 주행 중 · 사람이 앉은 좌석 · 전동이 아닌 좌석은 막음 (실행기에서도 한 번 더 확인).
 */
@Composable
private fun SeatAdjustCard(
    ui: UiState,
    s: SeatView,
    foreAft: Int,
    backrest: Int,
    onFore: (Int) -> Unit,
    onBack: (Int) -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalPalette.current
    val blocked = when {
        ui.isDriving -> "주행 중에는 움직이지 않아요"
        s.occupied == true -> "사람이 앉아 있어서 움직이지 않아요"
        !s.movable -> "이 좌석은 전동이 아니에요 · 손으로 맞춰 주세요"
        ui.run?.finished == false -> "다른 좌석을 맞추는 중이에요"
        else -> null
    }
    Column(Modifier.fillMaxWidth().designCard(p).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${s.id.labelKo} 직접 맞추기", color = p.text, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            GhostButton("닫기", onClose, height = 40.dp, fontSize = 13.sp)
        }
        if (s.foreAftMove) {
            SettingRow("앞뒤", "지금 ${s.foreAft?.let { "$it%" } ?: "모름"} · 100% = 맨 앞") {
                Stepper("$foreAft%", { onFore((foreAft - 5).coerceAtLeast(0)) }, { onFore((foreAft + 5).coerceAtMost(100)) }, "앞뒤", foreAft > 0, foreAft < 100)
            }
        }
        if (s.backrestMove) {
            SettingRow("등받이", "지금 ${s.backrest?.let { "${backrestDeg(it)}°" } ?: "모름"} · 숫자가 작을수록 세움", last = true) {
                Stepper("${backrestDeg(backrest)}°", { onBack((backrest + 10).coerceAtMost(100)) }, { onBack((backrest - 10).coerceAtLeast(0)) }, "등받이 각도", backrest < 100, backrest > 0)
            }
        }
        if (blocked != null) {
            Text(blocked, color = p.warn, fontSize = 14.sp)
        } else {
            GradientButton("맞추기 · 5초 뒤 움직여요", onSend, Modifier.fillMaxWidth(), height = 52.dp, fontSize = 16.sp)
            Text("P단에서만 · 한 좌석씩 · 문이 열리면 바로 멈춰요", color = p.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 좌석 실행 진행 (직접 맞춤 · 휴대폰 요청): 카운트다운 [멈추기], 단계 결과, [되돌리기] */
@Composable
private fun RunCard(ui: UiState, vm: MainViewModel) {
    val p = LocalPalette.current
    val run = ui.run ?: return
    Column(
        Modifier.fillMaxWidth().designCard(p).border(1.dp, p.accentRing, RoundedCornerShape(28.dp)).padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(run.reasonKo, color = p.accentText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        val countdown = run.countdown
        if (countdown != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${countdown}초 뒤 좌석이 움직여요 · 좌석 주변을 확인하세요", color = p.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                GhostButton("멈추기", { vm.controller.runner.cancel() }, height = 48.dp, fontSize = 15.sp)
            }
        }
        for (st in run.steps) {
            Text("${st.step.seat.labelKo} · ${st.step.action.labelKo} — ${st.outcome.labelKo}${st.detail?.let { " ($it)" } ?: ""}", color = p.sub, fontSize = 14.sp)
        }
        if (run.finished) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (run.canUndo && !ui.isDriving) GhostButton("되돌리기", { vm.controller.runner.undoLast() }, height = 44.dp, fontSize = 14.sp)
                GhostButton("닫기", { vm.controller.runner.dismiss() }, height = 44.dp, fontSize = 14.sp)
            }
        } else if (countdown == null) {
            GhostButton("멈추기", { vm.controller.runner.cancel() }, height = 44.dp, fontSize = 14.sp)
        }
    }
}

/** 트렁크 어림값 (휴대폰 3D 시뮬의 트렁크 카드와 같은 내용) */
@Composable
private fun TrunkCard(t: TrunkEstimate) {
    val p = LocalPalette.current
    val over = t.usedLiters > t.liters
    Column(Modifier.fillMaxWidth().designCard(p).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("트렁크 · 어림값", color = p.sub, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(if (t.rows >= 3) "3열 차" else "2열 차", color = p.sub, fontSize = 13.sp)
        }
        Text(
            if (t.usedLiters == 0) "약 ${t.liters}L · 실을 짐 없음" else "짐 ${t.usedLiters}L / 트렁크 약 ${t.liters}L",
            color = if (over) p.warn else p.text, fontSize = 20.sp, fontWeight = FontWeight.Medium,
        )
        val ratio = if (t.liters <= 0) 0f else (t.usedLiters.toFloat() / t.liters).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(p.chip)) {
            Box(Modifier.fillMaxWidth(ratio).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (over) p.warn else LuggageColor.trunk))
        }
        if (over) Text("${t.overflowLiters}L 넘쳐요 · 일부는 좌석 쪽에 실어야 해요", color = p.warn, fontSize = 14.sp)
        if (t.folded.isNotEmpty()) Text("짐 때문에 ${t.folded.joinToString { it.labelKo }} 접기 (직접)", color = p.text, fontSize = 14.sp)
        if (t.slideLiters != 0) {
            Text(
                if (t.slideLiters > 0) "뒷줄 좌석이 앞으로 가 있어 약 ${t.slideLiters}L 넓어요" else "뒷줄 좌석이 뒤로 가 있어 약 ${-t.slideLiters}L 좁아요",
                color = p.sub, fontSize = 14.sp,
            )
        }
        Text("${t.basisKo} · 트렁크 센서가 없어 좌석 위치로 어림한 값이에요", color = p.muted, fontSize = 12.sp)
    }
}

/** 휴대폰 앱이 찍는 연결 QR (내용: everyseat://pair?code=6자리) */
private fun pairQr(code: String): ImageBitmap {
    val m = QRCodeWriter().encode("everyseat://pair?code=$code", BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until m.height) for (x in 0 until m.width) {
        bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    return bmp.asImageBitmap()
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("휴대폰 연결 코드", color = p.sub, fontSize = 12.sp)
                    Text(code.chunked(3).joinToString(" "), color = p.text, fontSize = 28.sp, fontFamily = Montserrat, letterSpacing = 3.sp)
                    Text(
                        if (s.serverReachable) "Baby Seat 휴대폰 앱에 입력하거나 QR을 찍으세요" else "중계 서버에 연결 안 됨",
                        color = if (s.serverReachable) p.sub else p.warn, fontSize = 12.sp,
                    )
                }
                val qr = remember(code) { pairQr(code) }
                Image(
                    qr, contentDescription = "휴대폰 연결 QR",
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None, // 작은 QR을 키울 때 흐려지지 않게
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(4.dp),
                )
            }
        }
    }
}

/** 여정을 아직 못 받았을 때: 휴대폰에서 보내 달라고 안내 */
@Composable
private fun WaitingCard(ui: UiState, modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.designCard(p).padding(horizontal = 24.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("이번 여정", color = p.sub, fontSize = 14.sp)
        Text("누가 타고 무엇을 싣는지 아직 몰라요", color = p.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text(
            if (ui.phone.connected) "보호자 휴대폰의 Baby Seat 앱에서 [차로 보내기]를 누르면 여기에 좌석 단계가 나와요"
            else "보호자 휴대폰에서 Baby Seat 앱을 열고 왼쪽 위 연결 코드를 넣어 주세요",
            color = p.sub, fontSize = 15.sp,
        )
        ui.nextFeedAt?.let { Text("다음 수유 ${it.hm()}", color = p.accentText, fontSize = 14.sp) }
    }
}

/**
 * 목적지: 내비에 찍힌 곳을 보여 주고, 여기서 찾거나 즐겨찾기 · 최근 목적지에서 고름.
 * 찾기는 내비 장소 검색 화면을 검색어와 함께 열고(NaviHelper SearchPlace), 고른 곳은 내비 목적지 콜백으로 들어옴.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DestinationCard(ui: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    LaunchedEffect(Unit) { vm.controller.loadNaviPlaces() }
    var query by rememberSaveable { mutableStateOf("") }
    val r = ui.navi.route
    val search = { vm.controller.searchNaviPlace(query) }
    Column(modifier.fillMaxWidth().designCard(p).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("목적지", color = p.sub, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (r != null) Text("내비 안내 중", color = p.accentText, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                r?.destinationName ?: ui.trip?.destinationName ?: "아직 정하지 않았어요",
                color = if (r != null) p.text else p.sub, fontSize = 28.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.alignByBaseline().weight(1f, fill = false),
            )
            r?.let { Text(routeText(it.remainSeconds, it.remainMeters), color = p.sub, fontSize = 15.sp, maxLines = 1, modifier = Modifier.alignByBaseline()) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicTextField(
                value = query,
                onValueChange = { query = it.take(30) },
                singleLine = true,
                textStyle = TextStyle(color = p.text, fontSize = 16.sp),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.chip)
                    .border(1.dp, p.chipBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("어디로 갈까요? · 장소 이름", color = p.muted, fontSize = 16.sp)
                    inner()
                },
            )
            GradientButton("내비에서 찾기", search, height = 48.dp, enabled = query.isNotBlank(), fontSize = 15.sp)
        }
        val places = ui.navi.places
        if (places.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                places.take(6).forEach { pl -> PlaceChip(pl) { vm.controller.requestNaviRoute(pl.name, pl.point) } }
            }
        } else {
            Text("내비의 즐겨찾기 · 최근 목적지가 여기 보여요", color = p.muted, fontSize = 13.sp)
        }
    }
}

/** 즐겨찾기(★) · 최근 목적지 하나. 누르면 내비에 바로 경로 요청 */
@Composable
private fun PlaceChip(place: NaviPlace, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(999.dp)
    Text(
        (if (place.bookmark) "★ " else "") + place.name,
        color = p.chipText, fontSize = 14.sp, maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(p.chip)
            .border(1.dp, p.chipBorder, shape)
            .clickable(role = Role.Button, onClickLabel = "${place.name}로 안내", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
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

/** 좌석 단계마다 지금 상태 (홈의 체크리스트 · 3D 색) */
private fun boardItems(ui: UiState, plan: SeatPlan, guideDone: List<Int>): List<BoardItem> = plan.steps.mapIndexed { i, step ->
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
                    // 한 번 누르면 맞춤 (11/15 사용자: "누르고 있으면이 아니라 한 번에"). 5초 카운트다운 · [멈추기]는 SeatStepRunner 그대로
                    if (run?.finished == false) {
                        Text("맞추는 중…", color = p.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        GhostButton("멈추기", { vm.controller.holdApplyStop() }, height = 48.dp, fontSize = 15.sp)
                    } else {
                        GradientButton(
                            if (ui.isDriving) "주행 중에는 움직이지 않아요" else "적용 · 누르면 맞춰요",
                            { vm.controller.holdApplyStart() },
                            Modifier.weight(1f),
                            height = 52.dp,
                            enabled = !ui.isDriving && plan.sdkSteps.isNotEmpty(),
                            fontSize = 16.sp,
                        )
                    }
                    if (run?.canUndo == true) GhostButton("되돌리기", { vm.controller.runner.undoLast() }, height = 48.dp, fontSize = 14.sp)
                    if (ui.simulation) DashedChip("시뮬레이션")
                }
                Text(
                    "Vehicle SDK 좌석 제어 · 5초 뒤 움직임 · P · 빈 자리만 · 접기는 안내",
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
