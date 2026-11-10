package ai.moduui.jari.ui.components

import ai.moduui.core.model.SeatId
import ai.moduui.core.model.Side
import ai.moduui.jari.ui.theme.LocalPalette
import ai.moduui.jari.ui.theme.Palette
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 3D 화면에서 좌석 하나를 어떻게 보여 줄지 */
enum class SeatLook { NORMAL, ADJUST, CAR_SEAT, FOLD }

data class GlassSeat(
    val seat: SeatId,
    val look: SeatLook,
    /** 등받이가 수직에서 뒤로 누운 각도 (도). 접힘 = -80 */
    val reclineDeg: Float = 22f,
    /** 앞뒤 위치 (디자인 단위, + = 뒤쪽) */
    val slide: Float = 0f,
)

/**
 * 디자인 Boarding 의 "3D 유리 차체"를 그대로 옮김.
 * 원본은 CSS 3D (rotateX · rotateY · perspective 1700px) 로 옆면 SVG 두 장과 바닥·지붕 판, 좌석 상자를 띄운 것.
 * 같은 좌표(디자인 px)로 점을 만들고 같은 순서로 회전해 Canvas 에 투영함 — GL 없이 선 몇백 개라 차량 CPU 부담이 작음.
 */
private object GlassGeometry {
    /** SVG path 를 선분 점 목록으로 (윤곽마다 하나) */
    fun flatten(d: String, step: Float = 6f): List<Pair<List<Offset>, Boolean>> {
        val path = PathParser().parsePathString(d).toPath().asAndroidPath()
        val pm = android.graphics.PathMeasure(path, false)
        val out = ArrayList<Pair<List<Offset>, Boolean>>()
        val pos = FloatArray(2)
        do {
            val len = pm.length
            if (len > 0f) {
                val pts = ArrayList<Offset>()
                var dist = 0f
                while (dist < len) {
                    pm.getPosTan(dist, pos, null)
                    pts += Offset(pos[0], pos[1])
                    dist += step
                }
                pm.getPosTan(len, pos, null)
                pts += Offset(pos[0], pos[1])
                out += pts to pm.isClosed
            }
        } while (pm.nextContour())
        return out
    }

    // 원본 SVG (820×260, 왼쪽 = 차 앞)
    val body = flatten("M20 190 L20 150 Q24 128 60 122 L200 112 L292 50 Q302 40 322 40 L700 38 Q760 40 790 70 L806 120 L806 190 Q802 205 782 205 L720 205 A70 70 0 0 0 580 205 L240 205 A70 70 0 0 0 100 205 L40 205 Q20 205 20 190 Z")
    val windows = flatten("M300 56 L440 52 L440 112 L228 115 Z M456 52 L600 50 L600 110 L456 111 Z M616 50 L700 50 Q745 52 770 80 L782 108 L616 110 Z")
    val pillars = flatten("M448 46 L448 196 M608 46 L608 196")
    val wheels = flatten("M120 198 A50 50 0 1 0 220 198 A50 50 0 1 0 120 198 Z M600 198 A50 50 0 1 0 700 198 A50 50 0 1 0 600 198 Z")
    val hubs = flatten("M142 198 A28 28 0 1 0 198 198 A28 28 0 1 0 142 198 Z M622 198 A28 28 0 1 0 678 198 A28 28 0 1 0 622 198 Z")

    /** 옆면 SVG 좌표 → 3D (원본: left -410, top -130, translateZ(±165)) */
    fun side(pts: List<Offset>, z: Float) = pts.map { V3(it.x - 410f, it.y - 130f, z) }

    fun roundRectXZ(cx: Float, cz: Float, w: Float, d: Float, r: Float, y: Float): List<V3> {
        val out = ArrayList<V3>()
        val hw = w / 2 - r
        val hd = d / 2 - r
        val corners = listOf(Triple(hw, hd, 0.0), Triple(-hw, hd, PI / 2), Triple(-hw, -hd, PI), Triple(hw, -hd, 1.5 * PI))
        for ((x, z, a0) in corners) {
            for (k in 0..6) {
                val a = a0 + (PI / 2) * k / 6
                out += V3(cx + x + r * cos(a).toFloat(), y, cz + z + r * sin(a).toFloat())
            }
        }
        return out
    }

    fun ellipseXZ(rx: Float, rz: Float, y: Float, n: Int = 48) = List(n) { i ->
        val a = 2 * PI * i / n
        V3(rx * cos(a).toFloat(), y, rz * sin(a).toFloat())
    }

    val seatX = floatArrayOf(-60f, 100f, 250f)
    fun seatZ(s: SeatId) = (if (s.row == 3) 72f else 78f) * (if (s.side == Side.LEFT) 1f else -1f)
}

data class V3(val x: Float, val y: Float, val z: Float)

private class Prim(
    val pts: List<V3>,
    val closed: Boolean,
    val stroke: Color,
    val width: Float,
    val fill: Color? = null,
    val dashed: Boolean = false,
    val glow: Color? = null,
)

@Composable
fun GlassCar3D(
    seats: List<GlassSeat>,
    locked: Boolean,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    var rx by remember { mutableFloatStateOf(-22f) }
    var ry by remember { mutableFloatStateOf(-35f) }
    var dragging by remember { mutableStateOf(false) }

    // 원본: 40ms 마다 0.3° → 초당 7.5°
    LaunchedEffect(locked) {
        if (locked) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            if (!dragging) ry += (now - last) / 1e9f * 7.5f
            last = now
        }
    }

    Canvas(
        modifier
            .semantics { contentDescription = "차 안 3D 보기. 끌어서 돌려 볼 수 있어요" }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, drag ->
                    change.consume()
                    val k = 1f / density
                    ry += drag.x * k * 0.4f
                    rx = (rx - drag.y * k * 0.3f).coerceIn(-85f, 5f)
                }
            },
    ) {
        drawGlassCar(p, seats, rx, ry)
    }
}

private fun DrawScope.drawGlassCar(p: Palette, seats: List<GlassSeat>, rxDeg: Float, ryDeg: Float) {
    // 원본 무대 1000×600, 차 원점 (500, 330), 원근 기준 (500, 240)
    // 차(옆면 820)가 무대 폭의 대부분을 쓰도록 원본 1000×600 보다 조금 크게
    val scale = min(size.width / 900f, size.height / 520f)
    val ox = size.width / 2f
    val oy = size.height * 0.52f
    val persp = 1700f
    val cx = cos(Math.toRadians(rxDeg.toDouble())).toFloat()
    val sx = sin(Math.toRadians(rxDeg.toDouble())).toFloat()
    val cy = cos(Math.toRadians(ryDeg.toDouble())).toFloat()
    val sy = sin(Math.toRadians(ryDeg.toDouble())).toFloat()

    fun rot(v: V3): V3 {
        // CSS: rotateX(rx) rotateY(ry) → 먼저 Y, 다음 X
        val x1 = v.x * cy + v.z * sy
        val z1 = -v.x * sy + v.z * cy
        val y2 = v.y * cx - z1 * sx
        val z2 = v.y * sx + z1 * cx
        return V3(x1, y2, z2)
    }

    fun proj(v: V3): Offset {
        val f = persp / (persp - v.z)
        // 원근 기준이 차 원점보다 90px 위
        return Offset(ox + v.x * f * scale, oy + ((v.y + 90f) * f - 90f) * scale)
    }

    val line = p.glassLine
    val lineSoft = line.copy(alpha = line.alpha * 0.7f)
    val fill = p.glassFill
    val prims = ArrayList<Prim>()

    // 바닥 빛
    prims += Prim(GlassGeometry.ellipseXZ(450f, 210f, 124f), true, Color.Transparent, 0f, fill = p.glow.copy(alpha = if (p.isDark) 0.07f else 0.05f))
    prims += Prim(GlassGeometry.ellipseXZ(300f, 140f, 124f), true, Color.Transparent, 0f, fill = p.glow.copy(alpha = if (p.isDark) 0.08f else 0.05f))
    // 바닥판 760×330 r26 (y 72)
    prims += Prim(GlassGeometry.roundRectXZ(0f, 0f, 760f, 330f, 26f, 72f), true, lineSoft, 1.5f, fill = fill.copy(alpha = fill.alpha * 1.2f))
    // 보닛 · 앞유리 · 지붕 · 뒷면 · 앞면
    prims += Prim(listOf(V3(-405f, -6f, -155f), V3(-205f, -6f, -155f), V3(-205f, -6f, 155f), V3(-405f, -6f, 155f)), true, lineSoft, 1.5f, fill = fill.copy(alpha = 0.06f))
    run {
        val c = cos(Math.toRadians(-37.0)).toFloat()
        val s = sin(Math.toRadians(-37.0)).toFloat()
        fun ws(u: Float, w: Float) = V3(-155f + u * c, -51f + u * s, w)
        prims += Prim(listOf(ws(-56f, -150f), ws(56f, -150f), ws(56f, 150f), ws(-56f, 150f)), true, line, 1.5f, fill = fill.copy(alpha = 0.06f))
    }
    prims += Prim(listOf(V3(-105f, -92f, -150f), V3(305f, -92f, -150f), V3(305f, -92f, 150f), V3(-105f, -92f, 150f)), true, line.copy(alpha = line.alpha * 0.55f), 1.5f, fill = fill.copy(alpha = 0.04f))
    prims += Prim(listOf(V3(398f, -86f, -150f), V3(398f, -86f, 150f), V3(398f, 74f, 150f), V3(398f, 74f, -150f)), true, lineSoft, 1.5f, fill = fill.copy(alpha = 0.06f))
    prims += Prim(listOf(V3(-406f, -3f, -155f), V3(-406f, -3f, 155f), V3(-406f, 87f, 155f), V3(-406f, 87f, -155f)), true, lineSoft, 1.5f, fill = fill.copy(alpha = 0.06f))

    // 옆면 두 장
    for (z in floatArrayOf(165f, -165f)) {
        GlassGeometry.body.forEach { (pts, closed) -> prims += Prim(GlassGeometry.side(pts, z), closed, line, 2f, fill = fill) }
        GlassGeometry.windows.forEach { (pts, closed) -> prims += Prim(GlassGeometry.side(pts, z), closed, lineSoft, 1.5f, fill = fill.copy(alpha = fill.alpha * 1.25f)) }
        GlassGeometry.pillars.forEach { (pts, closed) -> prims += Prim(GlassGeometry.side(pts, z), closed, lineSoft, 2f) }
        GlassGeometry.wheels.forEach { (pts, closed) -> prims += Prim(GlassGeometry.side(pts, z), closed, line, 2.5f, fill = fill.copy(alpha = fill.alpha * 1.25f)) }
        GlassGeometry.hubs.forEach { (pts, closed) -> prims += Prim(GlassGeometry.side(pts, z), closed, lineSoft, 1.5f) }
    }

    // 좌석: 방석(가로 64×70) + 등받이(66×84, 뒤 끝에서 reclineDeg 만큼 누움) + 머리 받침
    for (s in seats) {
        val x0 = GlassGeometry.seatX[s.seat.row - 1] + s.slide
        val z0 = GlassGeometry.seatZ(s.seat)
        val y0 = 30f
        val (stroke, bg, dashed, glow) = when (s.look) {
            SeatLook.ADJUST -> Quad(p.accent, p.accent.copy(alpha = 0.30f), false, p.accent)
            SeatLook.CAR_SEAT -> Quad(p.text, p.text.copy(alpha = 0.16f), true, null)
            SeatLook.FOLD -> Quad(p.warn, p.warn.copy(alpha = 0.12f), true, null)
            SeatLook.NORMAL -> Quad(line.copy(alpha = 0.45f), line.copy(alpha = 0.06f), false, null)
        }
        fun at(x: Float, y: Float, z: Float) = V3(x0 + x, y0 + y, z0 + z)
        prims += Prim(listOf(at(-32f, 0f, -35f), at(32f, 0f, -35f), at(32f, 0f, 35f), at(-32f, 0f, 35f)), true, stroke, 2f, fill = bg, dashed = dashed, glow = glow)
        val r = Math.toRadians(s.reclineDeg.toDouble())
        val sr = sin(r).toFloat()
        val cr = cos(r).toFloat()
        fun back(yl: Float, z: Float) = at(32f - yl * sr, yl * cr, z)
        prims += Prim(listOf(back(0f, -33f), back(0f, 33f), back(-84f, 33f), back(-84f, -33f)), true, stroke, 2f, fill = bg, dashed = dashed, glow = glow)
        prims += Prim(listOf(back(-90f, -18f), back(-90f, 18f), back(-110f, 18f), back(-110f, -18f)), true, stroke, 2f, fill = bg, dashed = dashed)
    }

    // 먼 것부터 (CSS z+ = 보는 사람 쪽)
    val rotated = prims.map { prim -> prim to prim.pts.map(::rot) }
        .sortedBy { (_, pts) -> pts.sumOf { it.z.toDouble() } / pts.size }

    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
    for ((prim, pts) in rotated) {
        val path = Path()
        pts.forEachIndexed { i, v ->
            val o = proj(v)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        if (prim.closed) path.close()
        prim.fill?.let { drawPath(path, it) }
        if (prim.width > 0f) {
            val w = prim.width * scale.coerceAtLeast(0.8f)
            prim.glow?.let { g ->
                drawPath(path, g.copy(alpha = 0.18f), style = Stroke(w + 10f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, g.copy(alpha = 0.30f), style = Stroke(w + 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drawPath(path, prim.stroke, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = if (prim.dashed) dash else null))
        }
    }
}

private data class Quad(val stroke: Color, val bg: Color, val dashed: Boolean, val glow: Color?)

/**
 * 디자인 2-B "그 자리 3D": 좌석 하나만 크게. ghostDeg = 바뀌기 전(또는 바뀔) 등받이를 점선으로.
 * 주행 중에는 animate = false 로 회전을 끔 (운전 주의 분산 가이드: 주행 중 장식 애니메이션 금지).
 */
@Composable
fun SingleSeat3D(
    reclineDeg: Float,
    ghostDeg: Float?,
    color: Color,
    fill: Color,
    animate: Boolean,
    modifier: Modifier = Modifier,
) {
    var rx by remember { mutableFloatStateOf(-16f) }
    var ry by remember { mutableFloatStateOf(-40f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            if (!dragging) ry += (now - last) / 1e9f * 10f
            last = now
        }
    }
    Canvas(
        modifier
            .semantics { contentDescription = "좌석 3D 보기" }
            .pointerInput(animate) {
                if (!animate) return@pointerInput
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, drag ->
                    change.consume()
                    val k = 1f / density
                    ry += drag.x * k * 0.5f
                    rx = (rx - drag.y * k * 0.3f).coerceIn(-80f, 10f)
                }
            },
    ) {
        // 원본 무대 400×600, 좌석 원점 (200, 330), 원근 기준 (200, 276), perspective 900.
        // 원본은 2.6배지만 위아래 카드와 겹치지 않고 머리 받침이 잘리지 않게 2배로, 좌석을 조금 왼쪽 아래로
        val k = min(size.width / 400f, size.height / 600f)
        val ox = size.width * 0.44f
        val oy = size.height * 0.62f
        val big = 2.0f
        val cx = cos(Math.toRadians(rx.toDouble())).toFloat()
        val sx = sin(Math.toRadians(rx.toDouble())).toFloat()
        val cy = cos(Math.toRadians(ry.toDouble())).toFloat()
        val sy = sin(Math.toRadians(ry.toDouble())).toFloat()
        fun project(v: V3): Offset {
            val px = v.x * big
            val py = v.y * big
            val pz = v.z * big
            val x1 = px * cy + pz * sy
            val z1 = -px * sy + pz * cy
            val y2 = py * cx - z1 * sx
            val z2 = py * sx + z1 * cx
            val f = 900f / (900f - z2)
            return Offset(ox + x1 * f * k, oy + ((y2 + 54f) * f - 54f) * k)
        }
        fun poly(pts: List<V3>, stroke: Color?, bg: Color?, dashed: Boolean = false) {
            val path = Path()
            pts.forEachIndexed { i, v ->
                val o = project(v)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            path.close()
            bg?.let { drawPath(path, it) }
            stroke?.let {
                drawPath(path, it, style = Stroke(2.5f * k.coerceAtLeast(1f), join = StrokeJoin.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 7f)) else null))
            }
        }
        fun back(r: Float, yl: Float, z: Float): V3 {
            val a = Math.toRadians(r.toDouble())
            return V3(32f - yl * sin(a).toFloat(), yl * cos(a).toFloat(), z)
        }
        // 바닥 그림자
        poly(GlassGeometry.ellipseXZ(60f, 50f, 34f, 32), null, Color(0x4A141E64))
        poly(GlassGeometry.ellipseXZ(38f, 32f, 34f, 32), null, Color(0x3A141E64))
        // 방석 앞면 · 방석
        poly(listOf(V3(-32f, 0f, -35f), V3(-32f, 0f, 35f), V3(-32f, 16f, 35f), V3(-32f, 16f, -35f)), color, fill)
        poly(listOf(V3(-32f, 0f, -35f), V3(32f, 0f, -35f), V3(32f, 0f, 35f), V3(-32f, 0f, 35f)), color, fill)
        // 바뀌기 전(또는 바뀔) 등받이
        ghostDeg?.let { g ->
            poly(listOf(back(g, 0f, -33f), back(g, 0f, 33f), back(g, -84f, 33f), back(g, -84f, -33f)), Color.White.copy(alpha = 0.8f), null, dashed = true)
        }
        // 등받이 · 머리 받침
        poly(listOf(back(reclineDeg, 0f, -33f), back(reclineDeg, 0f, 33f), back(reclineDeg, -84f, 33f), back(reclineDeg, -84f, -33f)), color, fill)
        poly(listOf(back(reclineDeg, -90f, -18f), back(reclineDeg, -90f, 18f), back(reclineDeg, -110f, 18f), back(reclineDeg, -110f, -18f)), color, fill)
    }
}

/** 홈 화면의 차 옆모습 (같은 유리 차체 윤곽) + 바닥 청록 빛 */
@Composable
fun GlassCarSide(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Canvas(modifier.semantics { contentDescription = "차 옆모습" }) {
        val s = min(size.width / 840f, size.height / 250f)
        val w = 820f * s
        val left = (size.width - w) / 2f
        val top = (size.height - 230f * s) / 2f
        // 바닥 빛
        drawOval(
            Brush.radialGradient(
                listOf(p.glow.copy(alpha = if (p.isDark) 0.34f else 0.18f), Color.Transparent),
                center = Offset(size.width / 2f, top + 205f * s),
                radius = w * 0.55f,
            ),
            topLeft = Offset(left - 40f * s, top + 175f * s),
            size = androidx.compose.ui.geometry.Size(w + 80f * s, 64f * s),
        )
        fun draw(list: List<Pair<List<Offset>, Boolean>>, color: Color, width: Float, fill: Color?) {
            for ((pts, closed) in list) {
                val path = Path()
                pts.forEachIndexed { i, o ->
                    val x = left + o.x * s
                    val y = top + (o.y - 20f) * s
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (closed) path.close()
                fill?.let { drawPath(path, it) }
                drawPath(path, color, style = Stroke(width * s.coerceAtLeast(0.7f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        val line = p.glassLine
        draw(GlassGeometry.body, line, 2f, p.glassFill)
        draw(GlassGeometry.windows, line.copy(alpha = line.alpha * 0.7f), 1.5f, p.glassFill.copy(alpha = p.glassFill.alpha * 1.4f))
        draw(GlassGeometry.pillars, line.copy(alpha = line.alpha * 0.7f), 2f, null)
        draw(GlassGeometry.wheels, line, 2.5f, p.glassFill.copy(alpha = p.glassFill.alpha * 1.4f))
        draw(GlassGeometry.hubs, line.copy(alpha = line.alpha * 0.7f), 1.5f, null)
    }
}
