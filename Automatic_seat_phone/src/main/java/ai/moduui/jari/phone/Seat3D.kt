package ai.moduui.jari.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/** 차가 보낸 좌석 상황 → 3D 자세. 위치를 모르면 가운데 · 기본 각도로 */
fun SeatState.toPose(folded: Boolean = false) = SeatPose(
    id = id,
    x = if (right) 0.45f else -0.45f,
    row = row,
    foreAft = (foreAft ?: 50) / 100f,
    // 등받이 100 = 가장 세움(15°), 0 = 가장 눕힘(50°). 모르면 보통(약 24°)
    reclineDeg = backrestDeg(backrest ?: 75).toFloat(),
    highlight = canForeAft || canBackrest,
    occupied = occupied == true,
    folded = folded,
)

/** 3D 트렁크에 실을 짐 하나: 종류 칸 · 부피(L) */
data class TrunkItem(val slot: Slot, val liters: Int)

/**
 * 3D 트렁크에 실을 짐. liters 는 트렁크 어림 용량, 짐 부피 합이 넘치는 만큼은 주황으로.
 * items 는 여정에 넣은 짐의 실제 부피 (11/15: 예전에는 종류별 고정 크기 + 빈틈 25% 를 더해 들어가는 짐도 넘친다고 그렸음)
 */
data class TrunkLoad(val items: List<TrunkItem>, val liters: Int) {
    companion object {
        /** 개수만 알 때 (차가 받은 여정): 종류 기본 부피 */
        fun counts(strollers: Int, suitcases: Int, babyBags: Int, liters: Int) = TrunkLoad(
            List(strollers) { TrunkItem(Slot.STROLLER, Bag.STROLLER.defaultLiters) } +
                List(suitcases) { TrunkItem(Slot.SUITCASE, Bag.SUITCASE.defaultLiters) } +
                List(babyBags) { TrunkItem(Slot.BABY_BAG, Bag.BABY_BAG.defaultLiters) },
            liters,
        )
    }
}

/** 3D 에 그리는 짐 색 (범례에도 씀) */
object LuggageColor {
    val stroller = Color(0xFF8C7BD8)
    val suitcase = Color(0xFF4FA58E)
    val bag = Color(0xFFC09A57)
    val trunk = Color(0xFF6FB7D6)
}

private data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
}

private class Face(val pts: List<V3>, val color: Color, val edge: Color, val alpha: Float = 1f)

// 좌석 배치 (1 단위 ≈ 1m): 1열 쿠션 가운데 z = 0.55, 줄 간격 1.3, 앞뒤 이동 ±0.25
private fun seatZ(row: Int, foreAft: Float) = 0.55f - (row - 1) * 1.3f + (foreAft - 0.5f) * 0.5f

/** 좌석 등받이 뒤쪽 끝 (트렁크가 시작되는 곳) */
private fun seatBackZ(s: SeatPose) =
    seatZ(s.row, s.foreAft) - 0.24f - 0.08f - 0.3f * sin(Math.toRadians(s.reclineDeg.toDouble())).toFloat()

/**
 * 차 안 좌석과 트렁크를 3D 로 그림 (라이브러리 없이 원근 투영 + 면 정렬).
 * x 오른쪽, y 위, z 앞. 손가락으로 좌우로 끌면 돌아가고, 가만두면 천천히 돔.
 * 트렁크는 마지막 줄(접은 좌석은 건너뜀) 등받이 뒤부터 차 뒤 끝까지라서, 좌석을 앞으로 밀면 길어짐.
 */
@Composable
fun Seat3DView(
    seats: List<SeatPose>,
    modifier: Modifier = Modifier,
    load: TrunkLoad? = null,
    /** 고른 좌석 (흰 테두리) */
    selected: String? = null,
    /** 좌석을 누르면 (직접 맞추기) */
    onSeatTap: ((String) -> Unit)? = null,
) {
    // 좌석마다 화면 위 위치 (그릴 때 채움, 누른 곳과 가장 가까운 좌석을 찾음)
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
        val faces = mutableListOf<Face>()
        // 차 길이: 대시보드(앞)부터 마지막 줄 뒤 트렁크 끝까지. 3열 차는 트렁크가 짧음
        val carRear = carRearZ(seats)
        val rear = carRear - 0.06f
        val front = 1.85f
        box(faces, V3(0f, -0.04f, (front + rear) / 2), V3(1.9f, 0.08f, front - rear), C.card, C.line)
        // 앞쪽 대시보드 (차 앞이 어디인지 보이게)
        box(faces, V3(0f, 0.5f, 1.65f), V3(1.8f, 0.36f, 0.4f), Color(0xFF262D35), C.line)
        for (s in seats) seat(faces, s, s.id == selected)
        if (load != null) trunk(faces, seats, carRear, load)

        val centerZ = (front + rear) / 2 - 0.1f
        val cy = cos(Math.toRadians(yaw.toDouble())).toFloat()
        val sy = sin(Math.toRadians(yaw.toDouble())).toFloat()
        val cp = cos(Math.toRadians(pitch.toDouble())).toFloat()
        val sp = sin(Math.toRadians(pitch.toDouble())).toFloat()
        val camDist = 5.2f
        // 차가 길수록(3열 · 트렁크) 조금 작게
        val scale = min(size.width, size.height) * 4.9f / (front - rear).coerceAtLeast(3.3f)
        fun view(p: V3): V3 {
            // 차 가운데를 화면 가운데로
            val q = V3(p.x, p.y - 0.4f, p.z - centerZ)
            val x1 = q.x * cy - q.z * sy
            val z1 = q.x * sy + q.z * cy
            // 위에서 내려다보도록 x 축으로 기울임
            val y2 = q.y * cp + z1 * sp
            val z2 = -q.y * sp + z1 * cp
            return V3(x1, y2, z2 + camDist)
        }
        fun project(v: V3) = Offset(size.width / 2 + v.x / v.z * scale, size.height / 2 - v.y / v.z * scale)
        val light = V3(0.35f, 0.85f, -0.4f)
        centers.clear()
        for (s in seats) centers[s.id] = project(view(V3(s.x, 0.55f, seatZ(s.row, s.foreAft))))

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

private fun seat(out: MutableList<Face>, s: SeatPose, selected: Boolean = false) {
    val base = when {
        selected -> Color(0xFF2A93B8)
        s.folded -> Color(0xFF2E353F)
        s.highlight -> Color(0xFF1C6E8C)
        else -> Color(0xFF3A4350)
    }
    val edge = when {
        selected -> Color.White
        s.occupied -> C.warn
        s.highlight -> C.accent
        else -> Color(0xFF59636F)
    }
    val z = seatZ(s.row, s.foreAft)
    val cushion = V3(s.x, 0.38f, z)
    box(out, cushion, V3(0.52f, 0.12f, 0.52f), base, edge)
    // 등받이: 쿠션 뒤 끝을 축으로 뒤로 눕힘. 접은 좌석은 앞으로 쿠션 위에 엎음
    val a = Math.toRadians(if (s.folded) -88.0 else s.reclineDeg.toDouble())
    val pivot = V3(s.x, 0.44f, z - 0.24f)
    fun rot(p: V3): V3 {
        // 로컬 (0, y, zl): y 위, zl 앞 → x 축 기준으로 뒤로 a 만큼
        val c = cos(a).toFloat()
        val sn = sin(a).toFloat()
        return V3(p.x, p.y * c + p.z * sn, -p.y * sn + p.z * c)
    }
    fun rotBox(center: V3, size: V3) {
        val hx = size.x / 2
        val hy = size.y / 2
        val hz = size.z / 2
        val corners = listOf(
            V3(-hx, -hy, -hz), V3(hx, -hy, -hz), V3(hx, hy, -hz), V3(-hx, hy, -hz),
            V3(-hx, -hy, hz), V3(hx, -hy, hz), V3(hx, hy, hz), V3(-hx, hy, hz),
        ).map { val r = rot(it + center); V3(pivot.x + r.x, pivot.y + r.y, pivot.z + r.z) }
        addBoxFaces(out, corners, base, edge)
    }
    rotBox(V3(0f, 0.34f, -0.05f), V3(0.5f, 0.68f, 0.12f))
    if (!s.folded) rotBox(V3(0f, 0.8f, -0.05f), V3(0.26f, 0.16f, 0.1f))
}

/**
 * 트렁크: 왼쪽 · 오른쪽 절반씩, 그쪽 마지막 펼친 좌석의 등받이 뒤부터 차 뒤 끝까지 (반투명 상자).
 * 짐은 큰 것부터 뒤쪽 바닥에 줄지어 놓고, 자리가 없으면 위에 쌓음. 용량을 넘는 짐은 빨갛게.
 */
private data class Shape(val w: Float, val h: Float, val d: Float, val liters: Int, val color: Color)

/** 차 뒤 끝 z (마지막 줄 기준). 3D 그리기와 짐 맞추기(trunkFits)가 같은 값을 씀 */
private fun carRearZ(seats: List<SeatPose>): Float {
    val lastRow = seats.maxOfOrNull { it.row } ?: 2
    return seatZ(lastRow, 0.5f) - 0.3f - if (lastRow >= 3) 0.75f else 0.95f
}

private const val TRUNK_H = 0.85f

/** 트렁크 한쪽(왼 · 오른) 앞 끝 = 그쪽 마지막 펼친 좌석 등받이 뒤 */
private fun trunkFront(seats: List<SeatPose>, carRear: Float, right: Boolean): Float {
    val side = seats.filter { (it.x > 0) == right && !it.folded }
    val last = side.maxByOrNull { it.row } ?: seats.filter { !it.folded }.maxByOrNull { it.row }
    return if (last == null) carRear + 0.8f else seatBackZ(last)
}

private class Packed(val center: V3, val size: V3, val color: Color, val over: Boolean)

/**
 * 짐을 트렁크에 놓아 봄: 큰 것부터 뒤쪽 바닥에 줄지어, 길면 돌려서(가로로) 눕히고, 자리가 없으면 위에 쌓음.
 * 높이를 넘거나 용량(L)을 넘으면 over.
 */
private fun pack(seats: List<SeatPose>, carRear: Float, load: TrunkLoad): List<Packed> {
    fun frontOf(right: Boolean) = trunkFront(seats, carRear, right)
    data class Item(val w: Float, val h: Float, val d: Float, val liters: Int, val color: Color)
    fun shaped(t: TrunkItem): Item {
        val (w, h, d, baseL, color) = when (t.slot) {
            Slot.STROLLER -> Shape(0.55f, 0.3f, 0.8f, 130, LuggageColor.stroller)
            Slot.BABY_BAG -> Shape(0.36f, 0.24f, 0.26f, 30, LuggageColor.bag)
            else -> Shape(0.45f, 0.27f, 0.68f, 90, LuggageColor.suitcase)
        }
        val k = Math.cbrt(t.liters.coerceAtLeast(5).toDouble() / baseL).toFloat()
        return Item(w * k, h * k, d * k, t.liters, color)
    }
    val items = load.items.map(::shaped).sortedByDescending { it.liters }
    val minX = -0.82f
    val maxX = 0.82f
    var layerY = 0.02f
    var layerH = 0f
    var cx = minX
    var cz = carRear + 0.05f
    var lineD = 0f
    var usedLiters = 0f
    val out = mutableListOf<Packed>()
    for (item in items) {
        usedLiters += item.liters
        var over = usedLiters > load.liters
        var placed: V3? = null
        var size = V3(item.w, item.h, item.d)
        var guard = 0
        while (placed == null && guard++ < 40) {
            // 그대로 · 돌려서(가로 ↔ 세로) 중 들어가는 쪽
            val tries = listOf(item.w to item.d, item.d to item.w)
            val fit = tries.firstOrNull { (w, d) -> cx + w <= maxX && cz + d <= frontOf(cx + w / 2 > 0) - 0.02f }
            if (fit != null) {
                val (w, d) = fit
                placed = V3(cx + w / 2, layerY + item.h / 2, cz + d / 2)
                size = V3(w, item.h, d)
                cx += w + 0.03f
                lineD = max(lineD, d)
                layerH = max(layerH, item.h)
            } else if (cx > minX) {
                // 이 줄 끝: 다음 줄 (앞쪽으로)
                cx = minX; cz += lineD + 0.03f; lineD = 0f
                if (tries.all { (_, d) -> cz + d > frontOf(false) - 0.02f && cz + d > frontOf(true) - 0.02f }) {
                    layerY += layerH + 0.02f; layerH = 0f
                    cz = carRear + 0.05f
                }
            } else {
                // 이 층에 더 못 놓음 → 위에 쌓기
                layerY += layerH + 0.02f; layerH = 0f
                cx = minX; cz = carRear + 0.05f; lineD = 0f
            }
            if (placed == null && layerY + item.h > TRUNK_H) {
                // 트렁크에 안 들어감: 위로 삐져나오게
                placed = V3(0f, TRUNK_H + item.h / 2, carRear + 0.05f + item.d / 2)
                over = true
            }
        }
        if (placed != null) out += Packed(placed, size, item.color, over)
    }
    return out
}

/** 이 좌석 자세로 짐이 트렁크에 다 들어가는지 (3D 와 같은 셈). AI 자세에서 마지막 줄을 얼마나 당길지 찾을 때 씀 */
fun trunkFits(seats: List<SeatPose>, load: TrunkLoad): Boolean =
    pack(seats, carRearZ(seats), load).none { it.over }

private fun trunk(out: MutableList<Face>, seats: List<SeatPose>, carRear: Float, load: TrunkLoad) {
    for (right in listOf(false, true)) {
        val f = trunkFront(seats, carRear, right)
        val x = if (right) 0.43f else -0.43f
        box(out, V3(x, TRUNK_H / 2 + 0.01f, (f + carRear) / 2), V3(0.86f, TRUNK_H, f - carRear), LuggageColor.trunk, LuggageColor.trunk, alpha = 0.13f)
    }
    // 뒷범퍼 (차 뒤가 어디인지)
    box(out, V3(0f, 0.12f, carRear - 0.03f), V3(1.9f, 0.24f, 0.06f), Color(0xFF262D35), C.line)
    for (p in pack(seats, carRear, load)) {
        val color = if (p.over) C.warn else p.color
        box(out, p.center, p.size, color, if (p.over) C.warn else color.copy(alpha = 1f))
    }
}

private fun box(out: MutableList<Face>, c: V3, s: V3, color: Color, edge: Color, alpha: Float = 1f) {
    val hx = s.x / 2
    val hy = s.y / 2
    val hz = s.z / 2
    val corners = listOf(
        V3(-hx, -hy, -hz), V3(hx, -hy, -hz), V3(hx, hy, -hz), V3(-hx, hy, -hz),
        V3(-hx, -hy, hz), V3(hx, -hy, hz), V3(hx, hy, hz), V3(-hx, hy, hz),
    ).map { it + c }
    addBoxFaces(out, corners, color, edge, alpha)
}

/** 꼭짓점 8개 → 바깥을 향하는 면 6개 (반시계) */
private fun addBoxFaces(out: MutableList<Face>, k: List<V3>, color: Color, edge: Color, alpha: Float = 1f) {
    val idx = listOf(
        listOf(0, 3, 2, 1), // 뒤 (z-)
        listOf(4, 5, 6, 7), // 앞 (z+)
        listOf(0, 1, 5, 4), // 아래
        listOf(3, 7, 6, 2), // 위
        listOf(0, 4, 7, 3), // 왼쪽
        listOf(1, 2, 6, 5), // 오른쪽
    )
    for (f in idx) out += Face(f.map { k[it] }, color, edge, alpha)
}
