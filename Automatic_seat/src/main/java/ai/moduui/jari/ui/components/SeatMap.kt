package ai.moduui.jari.ui.components

import ai.moduui.core.model.BabyState
import ai.moduui.core.model.SeatId
import ai.moduui.core.model.Side
import ai.moduui.core.seat.Executor
import ai.moduui.core.seat.Occupant
import ai.moduui.core.seat.SeatAction
import ai.moduui.core.seat.SeatPlan
import ai.moduui.core.seat.TrunkMode
import ai.moduui.jari.control.RunState
import ai.moduui.jari.control.StepOutcome
import ai.moduui.jari.ui.theme.LocalPalette
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 위에서 본 차 안 (Figma 1-B 3D 유리 차체의 2D 버전).
 * 차체·앞유리·바퀴·핸들은 Canvas 로 한 번 그리는 정적인 평면도 — 3D 렌더링은 차량 CPU 5% 기준(주행 중)을 넘기 쉬움.
 */
@Composable
fun SeatMap(
    plan: SeatPlan?,
    run: RunState?,
    occupancy: Map<SeatId, Boolean?>,
    modifier: Modifier = Modifier,
    highlight: Set<SeatId> = emptySet(),
    baby: BabyState? = null,
    openDoors: Set<SeatId> = emptySet(),
    cellHeight: Dp = 92.dp,
) {
    val p = LocalPalette.current
    val hood = 78.dp
    val side = 40.dp
    val rowGap = 10.dp
    val seatGap = 14.dp
    val bodyColor = if (p.isDark) Color(0xFF1E2128) else Color(0xFFF8FAFC)
    val outline = p.muted.copy(alpha = 0.45f)
    val tire = if (p.isDark) Color(0xFF07080A) else Color(0xFF374151)

    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            val inset = 16.dp.toPx()
            val cellH = cellHeight.toPx()
            val gap = rowGap.toPx()
            fun rowCenter(r: Int) = hood.toPx() + (r - 1) * (cellH + gap) + cellH / 2

            // 바퀴 · 사이드미러 (차체 뒤에 그려서 반쯤 가려지게)
            val wheelW = 14.dp.toPx()
            val wheelH = cellH * 0.78f
            for (r in intArrayOf(1, 3)) {
                for (x in floatArrayOf(inset - wheelW / 2, w - inset - wheelW / 2)) {
                    drawRoundRect(tire, Offset(x, rowCenter(r) - wheelH / 2), Size(wheelW, wheelH), CornerRadius(wheelW / 2))
                }
            }
            val mirrorW = 22.dp.toPx()
            val mirrorH = 12.dp.toPx()
            val mirrorY = hood.toPx() - 18.dp.toPx()
            drawOval(outline, Offset(inset - mirrorW + 4.dp.toPx(), mirrorY), Size(mirrorW, mirrorH))
            drawOval(outline, Offset(w - inset - 4.dp.toPx(), mirrorY), Size(mirrorW, mirrorH))

            // 차체: 앞은 둥글게, 뒤는 조금 덜 둥글게
            val front = CornerRadius(w * 0.20f, 70.dp.toPx())
            val rear = CornerRadius(w * 0.12f, 36.dp.toPx())
            val body = Path().apply {
                addRoundRect(RoundRect(inset, 0f, w - inset, h, front, front, rear, rear))
            }
            drawPath(body, bodyColor)
            drawPath(body, outline, style = Stroke(2.dp.toPx()))

            // 앞유리 · 대시보드
            val wsTop = 14.dp.toPx()
            val wsBottom = hood.toPx() - 34.dp.toPx()
            val windshield = Path().apply {
                moveTo(w * 0.30f, wsTop)
                lineTo(w * 0.70f, wsTop)
                lineTo(w * 0.80f, wsBottom)
                lineTo(w * 0.20f, wsBottom)
                close()
            }
            drawPath(windshield, p.accent.copy(alpha = 0.16f))
            drawPath(windshield, p.accent.copy(alpha = 0.35f), style = Stroke(1.5.dp.toPx()))
            drawLine(outline, Offset(w * 0.16f, wsBottom + 8.dp.toPx()), Offset(w * 0.84f, wsBottom + 8.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)

            // 핸들 (운전석 앞)
            val innerW = w - 2 * side.toPx()
            val cellW = (innerW - seatGap.toPx()) / 2
            val driverX = side.toPx() + cellW / 2
            drawCircle(p.sub, 12.dp.toPx(), Offset(driverX, hood.toPx() - 14.dp.toPx()), style = Stroke(3.dp.toPx()))

            // 열린 문: 차체 옆에서 바깥으로 벌어진 선
            for (seat in openDoors) {
                val top = hood.toPx() + (seat.row - 1) * (cellH + gap)
                val edge = if (seat.side == Side.LEFT) inset else w - inset
                val out = if (seat.side == Side.LEFT) -1f else 1f
                drawLine(p.warn, Offset(edge, top + 4.dp.toPx()), Offset(edge + out * 30.dp.toPx(), top + cellH * 0.85f), 5.dp.toPx(), StrokeCap.Round)
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(start = side, end = side, top = hood, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(rowGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (row in 1..3) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(seatGap)) {
                    for (seat in SeatId.entries.filter { it.row == row }) {
                        SeatCell(seat, plan, run, occupancy[seat], seat in highlight, baby, seat in openDoors, cellHeight, Modifier.weight(1f))
                    }
                }
            }
            val trunk = plan?.trunkMode ?: TrunkMode.ALL_UP
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(p.chip),
                contentAlignment = Alignment.Center,
            ) {
                Text("트렁크 · ${trunk.labelKo}${plan?.let { " · 짐 ${it.luggageLiters}L" } ?: ""}", color = p.sub, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun SeatCell(
    seat: SeatId,
    plan: SeatPlan?,
    run: RunState?,
    occupied: Boolean?,
    highlighted: Boolean,
    baby: BabyState?,
    doorOpen: Boolean,
    height: Dp,
    modifier: Modifier,
) {
    val p = LocalPalette.current
    val occupant = plan?.assignments?.get(seat) ?: if (seat.isDriver) Occupant.DRIVER else null
    val steps = plan?.steps.orEmpty().filter { it.seat == seat }
    val folded = steps.any { it.action == SeatAction.Fold }
    val changes = steps.count { it.executor == Executor.SDK_CONTROL }
    val outcomes = run?.steps.orEmpty().filter { it.step.seat == seat }.map { it.outcome }
    val running = StepOutcome.RUNNING in outcomes
    val babyHere = if (occupant == Occupant.INFANT_CAR_SEAT && baby != null && baby != BabyState.UNKNOWN) baby else null
    val babyColor = when (babyHere) {
        BabyState.ASLEEP -> p.ok
        BabyState.AWAKE, BabyState.DROWSY -> p.warn
        BabyState.CRYING, BabyState.HUNGRY -> p.danger
        else -> p.sub
    }
    val fill = when {
        folded -> p.chip
        occupant == Occupant.INFANT_CAR_SEAT -> p.warn.copy(alpha = if (p.isDark) 0.25f else 0.35f)
        occupant != null -> p.accent.copy(alpha = 0.18f)
        else -> p.card
    }
    val border = when {
        running -> p.accent
        doorOpen -> p.warn
        highlighted -> p.ok
        changes > 0 -> p.accent.copy(alpha = 0.7f)
        else -> p.cardBorder
    }
    val desc = buildString {
        append(seat.labelKo)
        occupant?.let { append(", ").append(it.labelKo) }
        babyHere?.let { append(", 아기 ").append(it.labelKo) }
        if (doorOpen) append(", 문 열림")
        if (folded) append(", 접기")
        if (changes > 0) append(", 바뀌는 것 ${changes}개")
    }
    val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 12.dp, bottomEnd = 12.dp)
    Column(
        modifier
            .height(height)
            .clip(shape)
            .background(fill)
            .border(if (running || doorOpen || highlighted || changes > 0) 2.dp else 1.dp, border, shape)
            .semantics { contentDescription = desc }
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(seat.labelKo, color = p.sub, fontSize = 13.sp, textAlign = TextAlign.Center)
        Text(
            when {
                folded -> "접기 (직접)"
                occupant != null -> occupant.labelKo
                else -> "비어 있음"
            },
            color = p.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1,
        )
        when {
            running -> Text("움직이는 중", color = p.accent, fontSize = 12.sp)
            doorOpen -> Text("문 열림", color = p.warn, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            babyHere != null -> Text(babyHere.labelKo, color = babyColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            changes > 0 -> Text("바뀌는 것 $changes", color = p.accent, fontSize = 12.sp)
            occupied == true -> Text("착좌 감지", color = p.muted, fontSize = 12.sp)
        }
    }
}

/** 단계 하나의 배지 색 */
@Composable
fun outcomeColor(o: StepOutcome): Color {
    val p = LocalPalette.current
    return when (o) {
        StepOutcome.DONE -> p.ok
        StepOutcome.SENT, StepOutcome.RUNNING -> p.accent
        StepOutcome.GUIDE, StepOutcome.PENDING -> p.warn
        StepOutcome.CANCELLED, StepOutcome.SKIPPED_OCCUPIED -> p.sub
        else -> p.danger
    }
}
