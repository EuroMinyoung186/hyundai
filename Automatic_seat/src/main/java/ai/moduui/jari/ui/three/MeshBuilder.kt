package ai.moduui.jari.ui.three

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** 위치·법선이 섞인 정점 배열 (x,y,z,nx,ny,nz) + 삼각형 인덱스 */
internal class Mesh(val data: FloatArray, val indices: IntArray)

/**
 * 아주 작은 메시 생성기.
 * 삼각형마다 "안쪽 기준점"을 받아서 바깥을 보도록 감는 방향을 자동으로 맞춤 →
 * 셰이더가 앞면(바깥)과 뒷면(안쪽)을 다른 색으로 칠할 수 있음 (차체 바깥은 흰색, 실내는 어두운 색).
 * 정점을 공유하면 부드러운 법선, 따로 만들면 각진 법선이 됨.
 */
internal class MeshBuilder {
    private var pos = FloatArray(3 * 2048)
    private var np = 0
    private var idx = IntArray(3 * 4096)
    private var ni = 0

    fun v(x: Float, y: Float, z: Float): Int {
        if ((np + 1) * 3 > pos.size) pos = pos.copyOf(pos.size * 2)
        pos[np * 3] = x
        pos[np * 3 + 1] = y
        pos[np * 3 + 2] = z
        return np++
    }

    fun tri(a: Int, b: Int, c: Int, rx: Float, ry: Float, rz: Float) {
        val ax = pos[a * 3]; val ay = pos[a * 3 + 1]; val az = pos[a * 3 + 2]
        val bx = pos[b * 3]; val by = pos[b * 3 + 1]; val bz = pos[b * 3 + 2]
        val cx = pos[c * 3]; val cy = pos[c * 3 + 1]; val cz = pos[c * 3 + 2]
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val wx = cx - ax; val wy = cy - ay; val wz = cz - az
        val nx = uy * wz - uz * wy
        val ny = uz * wx - ux * wz
        val nz = ux * wy - uy * wx
        if (nx * nx + ny * ny + nz * nz < 1e-14f) return // 납작한 삼각형은 버림
        val dx = (ax + bx + cx) / 3f - rx
        val dy = (ay + by + cy) / 3f - ry
        val dz = (az + bz + cz) / 3f - rz
        val flip = nx * dx + ny * dy + nz * dz < 0f
        if (ni + 3 > idx.size) idx = idx.copyOf(idx.size * 2)
        idx[ni++] = a
        if (flip) {
            idx[ni++] = c; idx[ni++] = b
        } else {
            idx[ni++] = b; idx[ni++] = c
        }
    }

    fun quad(a: Int, b: Int, c: Int, d: Int, rx: Float, ry: Float, rz: Float) {
        tri(a, b, c, rx, ry, rz)
        tri(a, c, d, rx, ry, rz)
    }

    fun build(): Mesh {
        val n = FloatArray(np * 3)
        var t = 0
        while (t < ni) {
            val a = idx[t]; val b = idx[t + 1]; val c = idx[t + 2]
            val ux = pos[b * 3] - pos[a * 3]; val uy = pos[b * 3 + 1] - pos[a * 3 + 1]; val uz = pos[b * 3 + 2] - pos[a * 3 + 2]
            val wx = pos[c * 3] - pos[a * 3]; val wy = pos[c * 3 + 1] - pos[a * 3 + 1]; val wz = pos[c * 3 + 2] - pos[a * 3 + 2]
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            for (k in intArrayOf(a, b, c)) {
                n[k * 3] += nx; n[k * 3 + 1] += ny; n[k * 3 + 2] += nz
            }
            t += 3
        }
        val data = FloatArray(np * 6)
        for (i in 0 until np) {
            val len = sqrt(n[i * 3] * n[i * 3] + n[i * 3 + 1] * n[i * 3 + 1] + n[i * 3 + 2] * n[i * 3 + 2]).takeIf { it > 1e-12f } ?: 1f
            data[i * 6] = pos[i * 3]; data[i * 6 + 1] = pos[i * 3 + 1]; data[i * 6 + 2] = pos[i * 3 + 2]
            data[i * 6 + 3] = n[i * 3] / len; data[i * 6 + 4] = n[i * 3 + 1] / len; data[i * 6 + 5] = n[i * 3 + 2] / len
        }
        return Mesh(data, idx.copyOf(ni))
    }
}

// ---- 도형 --------------------------------------------------------------------------------------

internal fun MeshBuilder.box(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float) {
    val x0 = cx - hx; val x1 = cx + hx; val y0 = cy - hy; val y1 = cy + hy; val z0 = cz - hz; val z1 = cz + hz
    fun face(vararg p: Float) {
        val a = v(p[0], p[1], p[2]); val b = v(p[3], p[4], p[5]); val c = v(p[6], p[7], p[8]); val d = v(p[9], p[10], p[11])
        quad(a, b, c, d, cx, cy, cz)
    }
    face(x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1)
    face(x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0)
    face(x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0)
    face(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1)
    face(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0)
    face(x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1)
}

private fun sp(w: Double, m: Float): Float = (sign(w) * abs(w).pow(m.toDouble())).toFloat()

/** 초타원체. e 가 작을수록 모서리가 둥근 상자, 1 이면 타원체. 좌석·사람·카시트에 씀. */
internal fun MeshBuilder.blob(
    cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float,
    e1: Float = 1f, e2: Float = 1f, nu: Int = 24, nv: Int = 14,
) {
    val grid = Array(nv + 1) { IntArray(nu) }
    for (i in 0..nv) {
        val lat = -PI / 2 + PI * i / nv
        val cl = sp(cos(lat), e1)
        val sl = sp(sin(lat), e1)
        for (j in 0 until nu) {
            val lon = -PI + 2 * PI * j / nu
            grid[i][j] = v(cx + rx * cl * sp(cos(lon), e2), cy + ry * sl, cz + rz * cl * sp(sin(lon), e2))
        }
    }
    for (i in 0 until nv) for (j in 0 until nu) {
        val j1 = (j + 1) % nu
        quad(grid[i][j], grid[i + 1][j], grid[i + 1][j1], grid[i][j1], cx, cy, cz)
    }
}

/** X 축 방향 원기둥 (바퀴) */
internal fun MeshBuilder.cylinderX(cx: Float, cy: Float, cz: Float, r: Float, hx: Float, seg: Int = 32, caps: Boolean = true) {
    val a = IntArray(seg); val b = IntArray(seg)
    for (k in 0 until seg) {
        val ang = 2 * PI * k / seg
        val y = (r * cos(ang)).toFloat(); val z = (r * sin(ang)).toFloat()
        a[k] = v(cx - hx, cy + y, cz + z)
        b[k] = v(cx + hx, cy + y, cz + z)
    }
    for (k in 0 until seg) {
        val k1 = (k + 1) % seg
        quad(a[k], b[k], b[k1], a[k1], cx, cy, cz)
    }
    if (!caps) return
    for (s in floatArrayOf(-1f, 1f)) {
        val x = cx + s * hx
        val c = v(x, cy, cz)
        val ring = IntArray(seg) { k ->
            val ang = 2 * PI * k / seg
            v(x, cy + (r * cos(ang)).toFloat(), cz + (r * sin(ang)).toFloat())
        }
        for (k in 0 until seg) tri(c, ring[k], ring[(k + 1) % seg], cx, cy, cz)
    }
}

/** X 축 방향 반원 통 (바퀴 하우스). 위쪽 반만. */
internal fun MeshBuilder.halfTubeX(x0: Float, x1: Float, cy: Float, cz: Float, r: Float, seg: Int = 18) {
    val a = IntArray(seg + 1); val b = IntArray(seg + 1)
    for (k in 0..seg) {
        val ang = PI * k / seg
        val y = (r * sin(ang)).toFloat(); val z = (r * cos(ang)).toFloat()
        a[k] = v(x0, cy + y, cz + z)
        b[k] = v(x1, cy + y, cz + z)
    }
    val mx = (x0 + x1) / 2
    for (k in 0 until seg) quad(a[k], b[k], b[k + 1], a[k + 1], mx, cy, cz)
    val c = v(x0, cy, cz)
    val ring = IntArray(seg + 1) { k ->
        val ang = PI * k / seg
        v(x0, cy + (r * sin(ang)).toFloat(), cz + (r * cos(ang)).toFloat())
    }
    for (k in 0 until seg) tri(c, ring[k], ring[k + 1], x1, cy, cz)
}

/** XY 평면 고리 (핸들, 카시트 손잡이) */
internal fun MeshBuilder.torus(bigR: Float, r: Float, nMaj: Int = 36, nMin: Int = 10) {
    val grid = Array(nMaj) { IntArray(nMin) }
    for (i in 0 until nMaj) {
        val a = 2 * PI * i / nMaj
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        for (j in 0 until nMin) {
            val b = 2 * PI * j / nMin
            val cb = cos(b).toFloat(); val sb = sin(b).toFloat()
            grid[i][j] = v((bigR + r * cb) * ca, (bigR + r * cb) * sa, r * sb)
        }
    }
    for (i in 0 until nMaj) {
        val i1 = (i + 1) % nMaj
        val am = 2 * PI * (i + 0.5) / nMaj
        val rx = (bigR * cos(am)).toFloat(); val ry = (bigR * sin(am)).toFloat()
        for (j in 0 until nMin) {
            val j1 = (j + 1) % nMin
            quad(grid[i][j], grid[i1][j], grid[i1][j1], grid[i][j1], rx, ry, 0f)
        }
    }
}

/** 두 점을 잇는 각진 막대 (필러, 루프랙, 등) */
internal fun MeshBuilder.beam(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, w: Float, h: Float) {
    var dx = bx - ax; var dy = by - ay; var dz = bz - az
    val len = sqrt(dx * dx + dy * dy + dz * dz)
    if (len < 1e-6f) return
    dx /= len; dy /= len; dz /= len
    val (hx, hy, hz) = if (abs(dy) > 0.7f) Triple(0f, 0f, 1f) else Triple(0f, 1f, 0f)
    var sx = dy * hz - dz * hy; var sy = dz * hx - dx * hz; var sz = dx * hy - dy * hx
    val sl = sqrt(sx * sx + sy * sy + sz * sz)
    sx = sx / sl * w / 2; sy = sy / sl * w / 2; sz = sz / sl * w / 2
    var ux = sy * dz - sz * dy; var uy = sz * dx - sx * dz; var uz = sx * dy - sy * dx
    val ul = sqrt(ux * ux + uy * uy + uz * uz)
    ux = ux / ul * h / 2; uy = uy / ul * h / 2; uz = uz / ul * h / 2
    val mx = (ax + bx) / 2; val my = (ay + by) / 2; val mz = (az + bz) / 2
    fun p(end: Int, s: Int, u: Int): FloatArray {
        val ox = if (end == 0) ax else bx; val oy = if (end == 0) ay else by; val oz = if (end == 0) az else bz
        return floatArrayOf(ox + s * sx + u * ux, oy + s * sy + u * uy, oz + s * sz + u * uz)
    }
    fun face(vararg q: FloatArray) {
        val i = q.map { v(it[0], it[1], it[2]) }
        quad(i[0], i[1], i[2], i[3], mx, my, mz)
    }
    face(p(0, 1, -1), p(1, 1, -1), p(1, 1, 1), p(0, 1, 1))
    face(p(0, -1, -1), p(0, -1, 1), p(1, -1, 1), p(1, -1, -1))
    face(p(0, -1, 1), p(0, 1, 1), p(1, 1, 1), p(1, -1, 1))
    face(p(0, -1, -1), p(1, -1, -1), p(1, 1, -1), p(0, 1, -1))
    face(p(0, -1, -1), p(0, 1, -1), p(0, 1, 1), p(0, -1, 1))
    face(p(1, -1, -1), p(1, -1, 1), p(1, 1, 1), p(1, 1, -1))
}

/** 위를 보는 바닥 사각형 */
internal fun MeshBuilder.floorQuad(x0: Float, z0: Float, x1: Float, z1: Float, y: Float) {
    val a = v(x0, y, z0); val b = v(x1, y, z0); val c = v(x1, y, z1); val d = v(x0, y, z1)
    quad(a, b, c, d, (x0 + x1) / 2, y - 1f, (z0 + z1) / 2)
}

/** 단면(점 배열)을 차례로 이어 면을 만듦. ref(i) = i 번째 단면의 안쪽 기준점 */
internal fun MeshBuilder.loft(sections: List<FloatArray>, closed: Boolean = false, ref: (Int) -> FloatArray) {
    val m = sections[0].size / 3
    val grid = sections.map { s -> IntArray(m) { j -> v(s[j * 3], s[j * 3 + 1], s[j * 3 + 2]) } }
    for (i in 0 until grid.size - 1) {
        val r = ref(i)
        val last = if (closed) m else m - 1
        for (j in 0 until last) {
            val j1 = (j + 1) % m
            quad(grid[i][j], grid[i + 1][j], grid[i + 1][j1], grid[i][j1], r[0], r[1], r[2])
        }
    }
}

/** 볼록한 다각형 뚜껑 (가운데 점에서 부채꼴) */
internal fun MeshBuilder.fan(points: FloatArray, rx: Float, ry: Float, rz: Float) {
    val n = points.size / 3
    var cx = 0f; var cy = 0f; var cz = 0f
    for (k in 0 until n) {
        cx += points[k * 3]; cy += points[k * 3 + 1]; cz += points[k * 3 + 2]
    }
    val c = v(cx / n, cy / n, cz / n)
    val ring = IntArray(n) { k -> v(points[k * 3], points[k * 3 + 1], points[k * 3 + 2]) }
    for (k in 0 until n) tri(c, ring[k], ring[(k + 1) % n], rx, ry, rz)
}
