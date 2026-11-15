package ai.moduui.jari.ui.components

import ai.moduui.jari.control.SeatView
import ai.moduui.jari.ui.theme.LocalPalette

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 3D 시뮬의 좌석 하나: 앞뒤 위치(0 = 맨 뒤, 1 = 맨 앞)와 등받이 각도(도, 뒤로 눕힌 만큼) */
data class SeatPose(
    val id: String,
    val x: Float,
    val row: Int,
    val foreAft: Float = 0.5f,
    val reclineDeg: Float = 15f,
    /** 전동으로 움직일 수 있는 좌석 (파란색) */
    val highlight: Boolean = false,
    /** 사람이 앉아 있음 (주황 테두리) */
    val occupied: Boolean = false,
    /** 짐 때문에 접은 좌석 (등받이를 앞으로 눕혀 그림) */
    val folded: Boolean = false,
)

/** 등받이 0..100 (100 = 가장 세움) → 뒤로 눕힌 각도. 휴대폰 앱과 같은 15°..50° */
fun backrestDeg(b: Int): Int = (50 - b.coerceIn(0, 100) * 35 / 100f).toInt()

/** 차 좌석 상황 → 3D 자세. 위치를 모르면 가운데 · 기본 각도로 (휴대폰 3D 시뮬과 같은 규칙) */
fun SeatView.toPose(foreAftOverride: Int? = null, backrestOverride: Int? = null, folded: Boolean = false) = SeatPose(
    id = id.name,
    x = if (id.side.name == "RIGHT") 0.45f else -0.45f,
    row = id.row,
    foreAft = ((foreAftOverride ?: foreAft) ?: 50) / 100f,
    reclineDeg = backrestDeg((backrestOverride ?: backrest) ?: 75).toFloat(),
    highlight = movable,
    occupied = occupied == true,
    folded = folded,
)

/** 3D 트렁크에 실을 짐. liters 는 트렁크 어림 용량, 짐이 넘치는 만큼은 빨갛게 */
data class TrunkLoad(val strollers: Int, val suitcases: Int, val babyBags: Int, val liters: Int)

/** 3D 에 그리는 짐 색 (범례에도 씀) */
object LuggageColor {
    val stroller = Color(0xFF8C7BD8)
    val suitcase = Color(0xFF4FA58E)
    val bag = Color(0xFFC09A57)
    val trunk = Color(0xFF6FB7D6)
}

/**
 * 3D 카드 · 장면 색. 밤 모드는 휴대폰 3D 시뮬과 같은 어두운 색, 낮 모드는 밝은 카드에 맞춘 색.
 * 차의 주야간 모드(시스템)를 따름 → [D3].
 */
data class Scene3DColors(
    val card: Color,
    val line: Color,
    val text: Color,
    val sub: Color,
    val accent: Color,
    val warn: Color,
    val manual: Color,
    /** 차 바닥 · 대시보드 · 뒷범퍼 */
    val floor: Color,
    val dash: Color,
    /** 좌석 면: 고른 좌석 · 접은 좌석 · 전동 좌석 · 수동 좌석 */
    val seatSelected: Color,
    val seatFolded: Color,
    val seatPowered: Color,
    val seatManual: Color,
    /** 고른 좌석 테두리 */
    val selectedEdge: Color,
    /** 좌석 칩 바탕 (고름 / 보통) */
    val chipSelected: Color,
    val chip: Color,
)

val Dark3D = Scene3DColors(
    card = Color(0xFF14181D),
    line = Color(0xFF262D35),
    text = Color(0xFFF2F5F7),
    sub = Color(0xFF8B95A1),
    accent = Color(0xFF3EC5F0),
    warn = Color(0xFFF5B841),
    manual = Color(0xFF59636F),
    floor = Color(0xFF14181D),
    dash = Color(0xFF262D35),
    seatSelected = Color(0xFF2A93B8),
    seatFolded = Color(0xFF2E353F),
    seatPowered = Color(0xFF1C6E8C),
    seatManual = Color(0xFF3A4350),
    selectedEdge = Color.White,
    chipSelected = Color(0xFF0F2229),
    chip = Color(0xFF1B2027),
)

val Light3D = Scene3DColors(
    card = Color(0xFFFFFFFF),
    line = Color(0xFFB4BEC8),
    text = Color(0xFF15191D),
    sub = Color(0xFF5E666E),
    accent = Color(0xFF0093C0),
    warn = Color(0xFFD97706),
    manual = Color(0xFF7C8691),
    floor = Color(0xFFE6EAEE),
    dash = Color(0xFFC9D1D9),
    seatSelected = Color(0xFF3CC8EC),
    seatFolded = Color(0xFFD3D9DF),
    seatPowered = Color(0xFF7FD3EE),
    seatManual = Color(0xFFB3BCC5),
    selectedEdge = Color(0xFF15191D),
    chipSelected = Color(0xFFE3F6FC),
    chip = Color(0xFFF2F4F6),
)

/** 지금 주야간 모드의 3D 색 */
val D3: Scene3DColors
    @Composable @ReadOnlyComposable get() = if (LocalPalette.current.isDark) Dark3D else Light3D

private data class BV3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: BV3) = BV3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: BV3) = BV3(x - o.x, y - o.y, z - o.z)
    fun cross(o: BV3) = BV3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun dot(o: BV3) = x * o.x + y * o.y + z * o.z
}

private class BFace(val pts: List<BV3>, val color: Color, val edge: Color, val alpha: Float = 1f)

// 좌석 배치 (1 단위 ≈ 1m): 1열 쿠션 가운데 z = 0.55, 줄 간격 1.3, 앞뒤 이동 ±0.25
private fun seatZ(row: Int, foreAft: Float) = 0.55f - (row - 1) * 1.3f + (foreAft - 0.5f) * 0.5f

/** 좌석 등받이 뒤쪽 끝 (트렁크가 시작되는 곳) */
private fun seatBackZ(s: SeatPose) =
    seatZ(s.row, s.foreAft) - 0.24f - 0.08f - 0.3f * sin(Math.toRadians(s.reclineDeg.toDouble())).toFloat()

/**
 * 휴대폰 앱 Seat3D.kt 를 옮긴 것 (같은 모양 · 같은 색).
 * 차 안 좌석과 트렁크를 3D 로 그림 (라이브러리 없이 원근 투영 + 면 정렬).
 * x 오른쪽, y 위, z 앞. 손가락으로 좌우로 끌면 돌아가고, 가만두면 천천히 돔.
 * 트렁크는 마지막 줄(접은 좌석은 건너뜀) 등받이 뒤부터 차 뒤 끝까지라서, 좌석을 앞으로 밀면 길어짐.
 */
@Composable
fun SeatBox3D(
    seats: List<SeatPose>,
    modifier: Modifier = Modifier,
    load: TrunkLoad? = null,
    /** 고른 좌석 (흰 테두리) */
    selected: String? = null,
    /** 좌석을 누르면 (직접 맞추기) */
    onSeatTap: ((String) -> Unit)? = null,
) {
    // 좌석마다 화면 위 위치 (그릴 때 채움, 누른 곳과 가장 가까운 좌석을 찾음)
    val c = D3
    val centers = remember { HashMap<String, Offset>() }
    var yaw by remember { mutableFloatStateOf(-35f) }
    // 위아래 끌기는 화면 스크롤에 양보하려고 기울기는 고정 (옆에서 비스듬히 봐야 등받이 각도가 잘 보임)
    val pitch = 30f
    var touching by remember { mutableStateOf(false) }
    val still by rememberUpdatedState(selected != null)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                // 좌석을 골라 맞추는 동안은 멈춰서 그 좌석이 잘 보이게
                if (last != 0L && !touching && !still) yaw += (t - last) / 1e9f * 8f
                last = t
            }
        }
    }
    Canvas(
        modifier.clipToBounds().pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { touching = true },
                onDragEnd = { touching = false },
                onDragCancel = { touching = false },
            ) { _, dx -> yaw += dx * 0.35f }
        }.pointerInput(onSeatTap) {
            val tap = onSeatTap ?: return@pointerInput
            detectTapGestures { p ->
                centers.minByOrNull { (it.value - p).getDistance() }
                    ?.takeIf { (it.value - p).getDistance() < 60.dp.toPx() }
                    ?.let { tap(it.key) }
            }
        },
    ) {
        val faces = mutableListOf<BFace>()
        // 차 길이: 대시보드(앞)부터 마지막 줄 뒤 트렁크 끝까지. 3열 차는 트렁크가 짧음
        val lastRow = seats.maxOfOrNull { it.row } ?: 2
        val carRear = seatZ(lastRow, 0.5f) - 0.3f - if (lastRow >= 3) 0.75f else 0.95f
        val rear = carRear - 0.06f
        val front = 1.85f
        box(faces, BV3(0f, -0.04f, (front + rear) / 2), BV3(1.9f, 0.08f, front - rear), c.floor, c.line)
        // 앞쪽 대시보드 (차 앞이 어디인지 보이게)
        box(faces, BV3(0f, 0.5f, 1.65f), BV3(1.8f, 0.36f, 0.4f), c.dash, c.line)
        for (s in seats) seat(faces, s, c, s.id == selected)
        if (load != null) trunk(faces, seats, carRear, load, c)

        val centerZ = (front + rear) / 2 - 0.1f
        val cy = cos(Math.toRadians(yaw.toDouble())).toFloat()
        val sy = sin(Math.toRadians(yaw.toDouble())).toFloat()
        val cp = cos(Math.toRadians(pitch.toDouble())).toFloat()
        val sp = sin(Math.toRadians(pitch.toDouble())).toFloat()
        val camDist = 5.2f
        // 차가 길수록(3열 · 트렁크) 조금 작게
        // 차 화면은 카드가 넓고 커서 휴대폰(4.9)보다 조금 작게 그려야 돌려도 안 잘림
        val scale = min(size.width, size.height) * 3.6f / (front - rear).coerceAtLeast(3.3f)
        fun view(p: BV3): BV3 {
            // 차 가운데를 화면 가운데로
            val q = BV3(p.x, p.y - 0.4f, p.z - centerZ)
            val x1 = q.x * cy - q.z * sy
            val z1 = q.x * sy + q.z * cy
            // 위에서 내려다보도록 x 축으로 기울임
            val y2 = q.y * cp + z1 * sp
            val z2 = -q.y * sp + z1 * cp
            return BV3(x1, y2, z2 + camDist)
        }
        fun project(v: BV3) = Offset(size.width / 2 + v.x / v.z * scale, size.height / 2 - v.y / v.z * scale)
        val light = BV3(0.35f, 0.85f, -0.4f)
        centers.clear()
        for (s in seats) centers[s.id] = project(view(BV3(s.x, 0.55f, seatZ(s.row, s.foreAft))))

        val drawn = faces.mapNotNull { f ->
            val v = f.pts.map(::view)
            val n = (v[1] - v[0]).cross(v[2] - v[0])
            // 뒷면 버림 (카메라는 원점 쪽에서 +z 를 봄)
            if (n.dot(v[0]) >= 0f) return@mapNotNull null
            val depth = v.sumOf { it.z.toDouble() }.toFloat() / v.size
            val wn = (f.pts[1] - f.pts[0]).cross(f.pts[2] - f.pts[0])
            val len = kotlin.math.sqrt(wn.dot(wn)).coerceAtLeast(1e-6f)
            val shade = 0.55f + 0.45f * max(0f, wn.dot(light) / len / 1.03f)
            Triple(depth, f, v.map(::project) to shade)
        }.sortedByDescending { it.first }

        for ((_, f, ps) in drawn) {
            val (pts, shade) = ps
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                close()
            }
            drawPath(path, f.color.copy(red = f.color.red * shade, green = f.color.green * shade, blue = f.color.blue * shade), alpha = f.alpha)
            drawPath(path, f.edge, style = Stroke(width = 1.2f), alpha = if (f.alpha < 1f) 0.7f else 1f)
        }
    }
}

private fun seat(out: MutableList<BFace>, s: SeatPose, c: Scene3DColors, selected: Boolean = false) {
    val base = when {
        selected -> c.seatSelected
        s.folded -> c.seatFolded
        s.highlight -> c.seatPowered
        else -> c.seatManual
    }
    val edge = when {
        selected -> c.selectedEdge
        s.occupied -> c.warn
        s.highlight -> c.accent
        else -> c.manual
    }
    val z = seatZ(s.row, s.foreAft)
    val cushion = BV3(s.x, 0.38f, z)
    box(out, cushion, BV3(0.52f, 0.12f, 0.52f), base, edge)
    // 등받이: 쿠션 뒤 끝을 축으로 뒤로 눕힘. 접은 좌석은 앞으로 쿠션 위에 엎음
    val a = Math.toRadians(if (s.folded) -88.0 else s.reclineDeg.toDouble())
    val pivot = BV3(s.x, 0.44f, z - 0.24f)
    fun rot(p: BV3): BV3 {
        // 로컬 (0, y, zl): y 위, zl 앞 → x 축 기준으로 뒤로 a 만큼
        val c = cos(a).toFloat()
        val sn = sin(a).toFloat()
        return BV3(p.x, p.y * c + p.z * sn, -p.y * sn + p.z * c)
    }
    fun rotBox(center: BV3, size: BV3) {
        val hx = size.x / 2
        val hy = size.y / 2
        val hz = size.z / 2
        val corners = listOf(
            BV3(-hx, -hy, -hz), BV3(hx, -hy, -hz), BV3(hx, hy, -hz), BV3(-hx, hy, -hz),
            BV3(-hx, -hy, hz), BV3(hx, -hy, hz), BV3(hx, hy, hz), BV3(-hx, hy, hz),
        ).map { val r = rot(it + center); BV3(pivot.x + r.x, pivot.y + r.y, pivot.z + r.z) }
        addBoxFaces(out, corners, base, edge)
    }
    rotBox(BV3(0f, 0.34f, -0.05f), BV3(0.5f, 0.68f, 0.12f))
    if (!s.folded) rotBox(BV3(0f, 0.8f, -0.05f), BV3(0.26f, 0.16f, 0.1f))
}

/**
 * 트렁크: 왼쪽 · 오른쪽 절반씩, 그쪽 마지막 펼친 좌석의 등받이 뒤부터 차 뒤 끝까지 (반투명 상자).
 * 짐은 큰 것부터 뒤쪽 바닥에 줄지어 놓고, 자리가 없으면 위에 쌓음. 용량을 넘는 짐은 빨갛게.
 */
private fun trunk(out: MutableList<BFace>, seats: List<SeatPose>, carRear: Float, load: TrunkLoad, c: Scene3DColors) {
    fun frontOf(right: Boolean): Float {
        val side = seats.filter { (it.x > 0) == right && !it.folded }
        val last = side.maxByOrNull { it.row } ?: seats.filter { !it.folded }.maxByOrNull { it.row }
        return if (last == null) carRear + 0.8f else seatBackZ(last)
    }
    val height = 0.85f
    for (right in listOf(false, true)) {
        val f = frontOf(right)
        val x = if (right) 0.43f else -0.43f
        box(out, BV3(x, height / 2 + 0.01f, (f + carRear) / 2), BV3(0.86f, height, f - carRear), LuggageColor.trunk, LuggageColor.trunk, alpha = 0.13f)
    }
    // 뒷범퍼 (차 뒤가 어디인지)
    box(out, BV3(0f, 0.12f, carRear - 0.03f), BV3(1.9f, 0.24f, 0.06f), c.dash, c.line)

    // 짐 크기 (m): 유모차는 접어서 눕힘, 캐리어는 눕힘. 부피는 차 앱 LuggageType 과 같은 값
    data class Item(val w: Float, val h: Float, val d: Float, val liters: Int, val color: Color)
    val items = buildList {
        repeat(load.strollers) { add(Item(0.55f, 0.3f, 0.8f, 130, LuggageColor.stroller)) }
        repeat(load.suitcases) { add(Item(0.45f, 0.27f, 0.68f, 90, LuggageColor.suitcase)) }
        repeat(load.babyBags) { add(Item(0.36f, 0.24f, 0.26f, 30, LuggageColor.bag)) }
    }
    val minX = -0.82f
    val maxX = 0.82f
    var layerY = 0.02f
    var layerH = 0f
    var cx = minX
    var cz = carRear + 0.05f
    var lineD = 0f
    var usedLiters = 0f
    for (it in items) {
        usedLiters += it.liters / 0.75f
        var over = usedLiters > load.liters
        var placed: BV3? = null
        var guard = 0
        while (placed == null && guard++ < 40) {
            if (cx + it.w > maxX) {
                cx = minX; cz += lineD + 0.03f; lineD = 0f
            }
            val mid = cx + it.w / 2
            if (cz + it.d <= frontOf(mid > 0) - 0.02f) {
                placed = BV3(mid, layerY + it.h / 2, cz + it.d / 2)
                cx += it.w + 0.03f
                lineD = max(lineD, it.d)
                layerH = max(layerH, it.h)
            } else if (cx > minX) {
                cx = minX; cz += lineD + 0.03f; lineD = 0f
                if (cz + it.d > frontOf(false) - 0.02f && cz + it.d > frontOf(true) - 0.02f) cz = Float.MAX_VALUE
            } else {
                // 이 층에 더 못 놓음 → 위에 쌓기
                layerY += layerH + 0.02f; layerH = 0f
                cx = minX; cz = carRear + 0.05f; lineD = 0f
                if (layerY + it.h > height) {
                    // 트렁크에 안 들어감: 위로 삐져나오게 그리고 빨갛게
                    placed = BV3(0f, height + it.h / 2, carRear + 0.05f + it.d / 2)
                    over = true
                    layerY = height + it.h
                }
            }
        }
        if (placed == null) continue
        val color = if (over) c.warn else it.color
        box(out, placed, BV3(it.w, it.h, it.d), color, if (over) c.warn else color.copy(alpha = 1f))
    }
}

private fun box(out: MutableList<BFace>, c: BV3, s: BV3, color: Color, edge: Color, alpha: Float = 1f) {
    val hx = s.x / 2
    val hy = s.y / 2
    val hz = s.z / 2
    val corners = listOf(
        BV3(-hx, -hy, -hz), BV3(hx, -hy, -hz), BV3(hx, hy, -hz), BV3(-hx, hy, -hz),
        BV3(-hx, -hy, hz), BV3(hx, -hy, hz), BV3(hx, hy, hz), BV3(-hx, hy, hz),
    ).map { it + c }
    addBoxFaces(out, corners, color, edge, alpha)
}

/** 꼭짓점 8개 → 바깥을 향하는 면 6개 (반시계) */
private fun addBoxFaces(out: MutableList<BFace>, k: List<BV3>, color: Color, edge: Color, alpha: Float = 1f) {
    val idx = listOf(
        listOf(0, 3, 2, 1), // 뒤 (z-)
        listOf(4, 5, 6, 7), // 앞 (z+)
        listOf(0, 1, 5, 4), // 아래
        listOf(3, 7, 6, 2), // 위
        listOf(0, 4, 7, 3), // 왼쪽
        listOf(1, 2, 6, 5), // 오른쪽
    )
    for (f in idx) out += BFace(f.map { k[it] }, color, edge, alpha)
}
