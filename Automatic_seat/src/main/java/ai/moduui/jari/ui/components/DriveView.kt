package ai.moduui.jari.ui.components

import ai.moduui.jari.control.UiState
import ai.moduui.jari.ui.hm
import ai.moduui.jari.ui.theme.LocalPalette
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 주행 화면 위쪽의 도로 뷰 + 경로 진행 막대.
 *
 * 실차 모드에서는 정지 그림이고 값(속도·남은 시간)만 1초마다 바뀜.
 * 차선이 흘러가는 애니메이션은 시뮬레이션 모드에서만 켬 — Pleos 운전 주의 분산 가이드(주행 중 장식 애니메이션 금지)는
 * 실차 기준이므로, 시연할 때만 "달리는 느낌"을 보여 줌.
 */
@Composable
fun DriveView(ui: UiState, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    val route = ui.navi.route
    val speedKmh = ui.driving.speedKmh ?: 0f
    val moving = ui.isDriving && speedKmh > 1f
    val animate = ui.simulation && moving

    // NaviHelper 는 남은 시간만 주므로, 이 목적지에서 본 가장 긴 남은 시간을 전체 길이로 씀
    val maxRemain = remember(route?.destinationName) { longArrayOf(0L) }
    if (route != null) maxRemain[0] = max(maxRemain[0], route.remainSeconds)
    val progress = if (route != null && maxRemain[0] > 0) (1f - route.remainSeconds.toFloat() / maxRemain[0]).coerceIn(0f, 1f) else 0f

    // 달린 거리(m). Canvas 그리기 단계에서만 읽어서 매 프레임 재구성 없이 다시 그리기만 함.
    val travelled = remember { mutableFloatStateOf(0f) }
    val speedMs = speedKmh / 3.6f
    LaunchedEffect(animate, speedMs) {
        if (!animate) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                travelled.floatValue = (travelled.floatValue + speedMs * (now - last) / 1_000_000_000f) % 600f
                last = now
            }
        }
    }

    val scene = if (p.isDark) DarkScene else LightScene
    val eta = ui.eta
    val remainMin = route?.let { (it.remainSeconds / 60).toInt() }

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(p.card)
            .border(1.dp, p.cardBorder, RoundedCornerShape(28.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(440.dp)
                .semantics {
                    contentDescription = "도로 뷰, 시속 ${speedKmh.roundToInt()}킬로미터" + (remainMin?.let { ", 남은 시간 ${durationKo(it)}" } ?: "")
                },
        ) {
            Canvas(Modifier.matchParentSize()) { drawRoadScene(travelled.floatValue, scene) }

            // 왼쪽 위: 목적지 · 남은 시간
            GlassChip(Modifier.align(Alignment.TopStart).padding(16.dp)) {
                if (route == null) {
                    Text("경로 안내 없음", color = p.sub, fontSize = 15.sp)
                    Text("내비에서 목적지를 정하면 보여요", color = p.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("${route.destinationName ?: "목적지"}까지", color = p.sub, fontSize = 15.sp)
                    Text(durationKo(remainMin ?: 0), color = p.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    route.remainMeters?.let { Text("${(it / 1000.0).roundToInt()}km 남음", color = p.sub, fontSize = 15.sp) }
                }
            }

            // 오른쪽 위: 도착 예상
            if (route != null && eta != null) {
                GlassChip(Modifier.align(Alignment.TopEnd).padding(16.dp), alignEnd = true) {
                    Text("아기와 함께 도착", color = p.sub, fontSize = 15.sp)
                    Text(eta.predictedArrival.hm(), color = p.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("내비 ${eta.naviArrival.hm()} · +${eta.extraMinutes}분", color = p.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // 왼쪽 아래: 속도 · 기어
            GlassChip(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${speedKmh.roundToInt()}", color = p.text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Text("km/h", color = p.sub, fontSize = 17.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
                GearRow(ui.driving.gear)
            }

            // 오른쪽 아래: 다음 정차 / 경유지
            val nextStop = eta?.stops?.firstOrNull()
            val waypoint = ui.navi.waypoints.firstOrNull()
            if (route != null && (nextStop != null || waypoint != null)) {
                GlassChip(Modifier.align(Alignment.BottomEnd).padding(16.dp), alignEnd = true) {
                    if (waypoint != null) {
                        Text("경유", color = p.sub, fontSize = 15.sp)
                        Text(waypoint, color = p.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    } else if (nextStop != null) {
                        Text("다음 정차 예정", color = p.sub, fontSize = 15.sp)
                        Text("${nextStop.type.labelKo} ${nextStop.at.hm()}", color = p.warn, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        val stops = if (eta != null && eta.naviDriveMinutes > 0) {
            eta.stops.map { s ->
                (progress + (1f - progress) * (s.afterDriveMinutes.toFloat() / eta.naviDriveMinutes)).coerceIn(0f, 1f) to "${s.type.labelKo} ${s.at.hm()}"
            }
        } else emptyList()
        RouteStrip(progress, stops, route?.destinationName ?: "도착")
    }
}

@Composable
private fun GlassChip(modifier: Modifier = Modifier, alignEnd: Boolean = false, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Box(modifier) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(p.card.copy(alpha = 0.86f))
                .border(1.dp, p.cardBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        ) { content() }
    }
}

@Composable
private fun GearRow(gear: String?) {
    val p = LocalPalette.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (g in listOf("P", "R", "N", "D")) {
            val on = g == gear
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) p.text else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(g, color = if (on) p.card else p.muted, fontSize = 17.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** 출발 ─●───◆──── 도착. ● = 내 차, ◆ = 예정 정차 */
@Composable
private fun RouteStrip(progress: Float, stops: List<Pair<Float, String>>, destName: String) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
            val cy = size.height / 2
            val track = 8.dp.toPx()
            val r = 12.dp.toPx()
            val x0 = r
            val x1 = size.width - r
            val xAt = { f: Float -> x0 + (x1 - x0) * f }
            drawRoundRect(p.chip, Offset(x0, cy - track / 2), Size(x1 - x0, track), CornerRadius(track / 2))
            drawRoundRect(p.accent, Offset(x0, cy - track / 2), Size(xAt(progress) - x0, track), CornerRadius(track / 2))
            drawCircle(p.sub, 6.dp.toPx(), Offset(x0, cy))
            drawCircle(p.text, 7.dp.toPx(), Offset(x1, cy))
            drawCircle(p.card, 3.dp.toPx(), Offset(x1, cy))
            for ((f, _) in stops) {
                val x = xAt(f)
                val d = 8.dp.toPx()
                val diamond = Path().apply {
                    moveTo(x, cy - d); lineTo(x + d, cy); lineTo(x, cy + d); lineTo(x - d, cy); close()
                }
                drawPath(diamond, p.warn)
                drawPath(diamond, p.card, style = Stroke(2.dp.toPx()))
            }
            val cx = xAt(progress)
            drawCircle(p.card, r, Offset(cx, cy))
            drawCircle(p.accent, r - 3.dp.toPx(), Offset(cx, cy))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(22.dp)) {
            val w = maxWidth
            Text("출발", color = p.sub, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterStart))
            Text(destName, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterEnd))
            for ((f, label) in stops) {
                if (f < 0.08f || f > 0.88f) continue // 양 끝 글자와 겹치지 않게
                Text(
                    label, color = p.warn, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.width(96.dp).offset(x = w * f - 48.dp).align(Alignment.CenterStart),
                )
            }
        }
    }
}

fun durationKo(minutes: Int): String = when {
    minutes >= 60 -> "${minutes / 60}시간 ${minutes % 60}분"
    else -> "${minutes}분"
}

// ---- 도로 그림 -------------------------------------------------------------------------------

private data class SceneColors(
    val skyTop: Color, val skyBottom: Color, val hills: Color, val ground: Color, val road: Color,
    val edge: Color, val lane: Color, val post: Color, val reflector: Color,
    val car: Color, val carShade: Color, val glass: Color, val tail: Color, val tire: Color, val lead: Color,
)

private val DarkScene = SceneColors(
    skyTop = Color(0xFF0B1220), skyBottom = Color(0xFF22344D), hills = Color(0xFF131C28), ground = Color(0xFF0E141B),
    road = Color(0xFF262B33), edge = Color(0xD9E5E7EB), lane = Color(0xB3E5E7EB), post = Color(0xFF5B6470), reflector = Color(0xFFF6D88A),
    car = Color(0xFFDDE2E7), carShade = Color(0xFFAEB6BE), glass = Color(0xFF1B2430), tail = Color(0xFFFF4D4D), tire = Color(0xFF0A0B0D),
    lead = Color(0xFF6B7684),
)

private val LightScene = SceneColors(
    skyTop = Color(0xFF8DBFE6), skyBottom = Color(0xFFE3EFF8), hills = Color(0xFFA6C2B0), ground = Color(0xFFCAD7C9),
    road = Color(0xFF7B838E), edge = Color(0xFFFFFFFF), lane = Color(0xE6FFFFFF), post = Color(0xFFF3F4F6), reflector = Color(0xFFE09A00),
    car = Color(0xFFF7F8FA), carShade = Color(0xFFC9CFD6), glass = Color(0xFF2B3542), tail = Color(0xFFE11D2E), tire = Color(0xFF1F2329),
    lead = Color(0xFF3F4A57),
)

private fun DrawScope.drawRoadScene(travelled: Float, c: SceneColors) {
    val w = size.width
    val h = size.height
    val horizon = h * 0.40f
    val cx = w / 2f

    drawRect(Brush.verticalGradient(listOf(c.skyTop, c.skyBottom), startY = 0f, endY = horizon), size = Size(w, horizon))
    val hills = Path().apply {
        moveTo(0f, horizon)
        lineTo(0f, horizon - h * 0.06f)
        cubicTo(w * 0.14f, horizon - h * 0.15f, w * 0.28f, horizon - h * 0.03f, w * 0.42f, horizon - h * 0.09f)
        cubicTo(w * 0.55f, horizon - h * 0.16f, w * 0.70f, horizon - h * 0.02f, w * 0.84f, horizon - h * 0.10f)
        cubicTo(w * 0.92f, horizon - h * 0.14f, w * 0.97f, horizon - h * 0.08f, w, horizon - h * 0.07f)
        lineTo(w, horizon)
        close()
    }
    drawPath(hills, c.hills)
    drawRect(c.ground, topLeft = Offset(0f, horizon), size = Size(w, h - horizon))

    // 원근: 거리 d(m) 의 화면 y = horizon + (h - horizon) * D0 / d. 도로 폭은 화면 y 에 비례.
    val d0 = 4f
    val roadHalfBottom = w * 0.46f
    fun y(d: Float) = horizon + (h - horizon) * d0 / d
    fun scale(yy: Float) = (yy - horizon) / (h - horizon)
    fun half(yy: Float) = roadHalfBottom * scale(yy)

    val road = Path().apply {
        moveTo(cx, horizon)
        lineTo(cx + roadHalfBottom, h)
        lineTo(cx - roadHalfBottom, h)
        close()
    }
    drawPath(road, c.road)

    fun laneQuad(f: Float, dNear: Float, dFar: Float, thick: Float, color: Color) {
        val yN = y(dNear)
        val yF = y(dFar)
        val tN = thick * scale(yN)
        val tF = thick * scale(yF)
        val path = Path().apply {
            moveTo(cx + f * half(yN) - tN, yN)
            lineTo(cx + f * half(yN) + tN, yN)
            lineTo(cx + f * half(yF) + tF, yF)
            lineTo(cx + f * half(yF) - tF, yF)
            close()
        }
        drawPath(path, color)
    }

    // 길가 표지봉 (25m 간격)
    run {
        var s = -(travelled % 25f)
        while (s < 300f) {
            if (s > d0) {
                val yy = y(s)
                val k = scale(yy)
                val postH = h * 0.16f * k
                val postW = max(w * 0.010f * k, 1.5f)
                for (sign in floatArrayOf(-1f, 1f)) {
                    val x = cx + sign * half(yy) * 1.10f
                    drawRect(c.post, Offset(x - postW / 2, yy - postH), Size(postW, postH))
                    drawRect(c.reflector, Offset(x - postW / 2, yy - postH), Size(postW, postH * 0.22f))
                }
            }
            s += 25f
        }
    }

    // 바깥 실선, 가운데 점선 2개 (3차로, 12m 주기 · 5m 칠)
    laneQuad(-0.96f, d0, 4000f, w * 0.007f, c.edge)
    laneQuad(0.96f, d0, 4000f, w * 0.007f, c.edge)
    run {
        var s = -(travelled % 12f)
        while (s < 260f) {
            val near = max(s, d0)
            val far = s + 5f
            if (far > d0) {
                laneQuad(-1f / 3f, near, far, w * 0.0055f, c.lane)
                laneQuad(1f / 3f, near, far, w * 0.0055f, c.lane)
            }
            s += 12f
        }
    }

    // 앞차 (왼쪽 차로, 같은 속도로 달려서 거리가 일정)
    run {
        val yy = y(48f)
        val k = scale(yy)
        drawCarRear(cx - (2f / 3f) * half(yy), yy, roadHalfBottom * 0.42f * k, c.lead, c.lead, c.glass, c.tail, c.tire)
    }

    // 내 차 (가운데 차로, 뒷모습)
    val carW = w * 0.25f
    val bottom = h - h * 0.05f
    drawOval(Color.Black.copy(alpha = 0.28f), Offset(cx - carW * 0.56f, bottom - carW * 0.06f), Size(carW * 1.12f, carW * 0.14f))
    drawCarRear(cx, bottom, carW, c.car, c.carShade, c.glass, c.tail, c.tire)
}

private fun DrawScope.drawCarRear(cx: Float, bottom: Float, width: Float, body: Color, shade: Color, glass: Color, tail: Color, tire: Color) {
    val hgt = width * 0.60f
    val top = bottom - hgt
    val tw = width * 0.16f
    val th = hgt * 0.24f
    drawRoundRect(tire, Offset(cx - width * 0.45f, bottom - th), Size(tw, th), CornerRadius(tw * 0.3f))
    drawRoundRect(tire, Offset(cx + width * 0.45f - tw, bottom - th), Size(tw, th), CornerRadius(tw * 0.3f))
    val cabin = Path().apply {
        moveTo(cx - width * 0.42f, top + hgt * 0.44f)
        lineTo(cx - width * 0.31f, top)
        lineTo(cx + width * 0.31f, top)
        lineTo(cx + width * 0.42f, top + hgt * 0.44f)
        close()
    }
    drawPath(cabin, body)
    drawRoundRect(body, Offset(cx - width / 2, top + hgt * 0.38f), Size(width, hgt * 0.50f), CornerRadius(width * 0.08f))
    drawRoundRect(shade, Offset(cx - width / 2, top + hgt * 0.74f), Size(width, hgt * 0.14f), CornerRadius(width * 0.06f))
    val win = Path().apply {
        moveTo(cx - width * 0.35f, top + hgt * 0.38f)
        lineTo(cx - width * 0.27f, top + hgt * 0.07f)
        lineTo(cx + width * 0.27f, top + hgt * 0.07f)
        lineTo(cx + width * 0.35f, top + hgt * 0.38f)
        close()
    }
    drawPath(win, glass)
    drawRoundRect(tail, Offset(cx - width * 0.47f, top + hgt * 0.47f), Size(width * 0.94f, hgt * 0.07f), CornerRadius(hgt * 0.035f))
    drawRoundRect(shade, Offset(cx - width * 0.11f, top + hgt * 0.60f), Size(width * 0.22f, hgt * 0.10f), CornerRadius(width * 0.015f))
}
