package ai.moduui.jari.ui.three

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3열 SUV 를 코드로 만든 모델 (단위 m, 앞 = -Z, 오른쪽 = +X, 위 = +Y, 원점 = 차 바닥 가운데).
 * 아래 차체는 불투명, 위쪽(유리 차체 · 파노라마 루프)은 반투명이라 좌석과 탑승자가 들여다보임 — Figma 1-B "3D 유리 차체".
 * 메시는 프로세스에서 한 번만 만들고, GL 화면마다 GPU 로 올림.
 */
internal object CarModel {
    const val ZF = -2.475f
    const val ZR = 2.475f
    private const val HW = 0.97f
    const val WHEEL_R = 0.36f
    const val WHEEL_X = 0.83f
    val WHEEL_Z = floatArrayOf(-1.55f, 1.55f)
    private const val ARCH_R = 0.45f
    private const val ZA = -1.25f   // 앞유리 아래
    private const val ZR1 = -0.35f  // 지붕 앞
    private const val ZR2 = 2.05f   // 지붕 뒤
    private const val ROOF = 1.72f
    const val FLOOR = 0.30f
    val ROW_Z = floatArrayOf(-0.25f, 0.65f, 1.50f)
    val SEAT_X = floatArrayOf(0.43f, 0.43f, 0.40f)
    const val STEER_X = -0.43f

    // ---- 모양 함수 ----

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun halfWidth(z: Float): Float {
        val df = z - ZF
        val dr = ZR - z
        val rf = 0.55f
        val rr = 0.38f
        var hw = HW
        if (df < rf) hw = HW - rf + sqrt(max(0f, rf * rf - (rf - df) * (rf - df)))
        if (dr < rr) hw = min(hw, HW - rr + sqrt(max(0f, rr * rr - (rr - dr) * (rr - dr))))
        return hw
    }

    fun belt(z: Float): Float =
        if (z < ZA) {
            val t = ((z - ZF) / (ZA - ZF)).coerceIn(0f, 1f)
            lerp(0.80f, 0.98f, 1f - (1f - t) * (1f - t))
        } else {
            0.98f + 0.08f * (z - ZA) / (ZR - ZA)
        }

    private fun bottom(z: Float): Float {
        val df = z - ZF
        val dr = ZR - z
        var b = 0.22f
        if (df < 0.5f) b += 0.14f * (1f - df / 0.5f).pow(2)
        if (dr < 0.5f) b += 0.16f * (1f - dr / 0.5f).pow(2)
        for (zw in WHEEL_Z) {
            val d = z - zw
            if (abs(d) < ARCH_R) b = max(b, WHEEL_R + sqrt(ARCH_R * ARCH_R - d * d))
        }
        return b
    }

    /** 옆면 x: 가운데 높이에서 가장 불룩함 */
    fun wallX(z: Float, y: Float): Float {
        val t = ((y - 0.22f) / (belt(z) - 0.22f)).coerceIn(0f, 1f)
        return halfWidth(z) * (0.955f + 0.045f * sin(PI * t).toFloat())
    }

    private fun roof(z: Float): Float = when {
        z <= ZA -> belt(z)
        z < ZR1 -> {
            val t = (z - ZA) / (ZR1 - ZA)
            belt(z) + (ROOF - belt(z)) * (1f - (1f - t).pow(1.6f))
        }
        z <= ZR2 -> ROOF + 0.025f * sin(PI * (z - ZR1) / (ZR2 - ZR1)).toFloat()
        else -> {
            val t = (z - ZR2) / (ZR - ZR2)
            ROOF - (ROOF - 1.32f) * t.pow(1.3f)
        }
    }

    /** 유리 차체 단면 위의 점. theta 0 = 오른쪽 벨트라인, π/2 = 지붕 가운데, π = 왼쪽 */
    private fun archPt(z: Float, theta: Double): FloatArray {
        val bl = belt(z)
        val top = max(roof(z), bl)
        val h = top - bl
        val bx = wallX(z, bl) - 0.006f
        val tx = bx * 0.80f
        val c = cos(theta)
        val s = sin(theta)
        val y = bl + h * abs(s).pow(0.45).toFloat()
        val a = if (h < 1e-4f) bx else bx + (tx - bx) * ((y - bl) / h)
        val x = (a * sign(c) * abs(c).pow(0.30)).toFloat()
        return floatArrayOf(x, y, z)
    }

    private fun floorHalf(z: Float): Float {
        for (zw in WHEEL_Z) if (abs(z - zw) < ARCH_R + 0.02f) return 0.60f
        return wallX(z, FLOOR) - 0.03f
    }

    private fun hoodSection(z: Float): FloatArray {
        val top = belt(z)
        val bx = wallX(z, top)
        val n = 11
        return FloatArray(n * 3) { i ->
            val k = i / 3
            val x = -bx + 2 * bx * k / (n - 1)
            when (i % 3) {
                0 -> x
                1 -> top + 0.05f * (1f - (x / bx) * (x / bx))
                else -> z
            }
        }
    }

    private const val WALL_K = 6

    private fun wallSection(z: Float, side: Float): FloatArray {
        val top = belt(z)
        val bot = bottom(z)
        val out = FloatArray((WALL_K + 2) * 3)
        for (k in 0..WALL_K) {
            val y = top + (bot - top) * k / WALL_K
            out[k * 3] = side * wallX(z, y); out[k * 3 + 1] = y; out[k * 3 + 2] = z
        }
        out[(WALL_K + 1) * 3] = side * (wallX(z, bot) - 0.12f); out[(WALL_K + 1) * 3 + 1] = bot; out[(WALL_K + 1) * 3 + 2] = z
        return out
    }

    private fun wallPoint(z: Float, side: Float, k: Int): FloatArray {
        val top = belt(z)
        val y = top + (bottom(z) - top) * k / WALL_K
        return floatArrayOf(side * wallX(z, y), y, z)
    }

    // ---- 차 메시 ----

    /** 바깥은 차체 색, 안쪽(뒷면)은 실내 색 */
    val body: Mesh by lazy {
        val b = MeshBuilder()
        val n = 110
        val zs = List(n + 1) { ZF + (ZR - ZF) * it / n }
        for (side in floatArrayOf(-1f, 1f)) b.loft(zs.map { wallSection(it, side) }) { i -> floatArrayOf(0f, 0.6f, zs[i]) }
        val hz = List(29) { ZF + (ZA + 0.04f - ZF) * it / 28 }
        b.loft(hz.map { hoodSection(it) }) { i -> floatArrayOf(0f, belt(hz[i]) - 1f, hz[i]) }
        // 앞 범퍼 면
        run {
            val pts = ArrayList<Float>()
            hoodSection(ZF).forEach { pts += it }
            for (k in 1..WALL_K) wallPoint(ZF, 1f, k).forEach { pts += it }
            for (k in WALL_K downTo 1) wallPoint(ZF, -1f, k).forEach { pts += it }
            b.fan(pts.toFloatArray(), 0f, 0.6f, ZF + 1f)
        }
        // 뒤 범퍼 면
        run {
            val pts = ArrayList<Float>()
            for (k in 0..WALL_K) wallPoint(ZR, 1f, k).forEach { pts += it }
            for (k in WALL_K downTo 0) wallPoint(ZR, -1f, k).forEach { pts += it }
            b.fan(pts.toFloatArray(), 0f, 0.6f, ZR - 1f)
        }
        b.build()
    }

    /** 바닥 · 바퀴 하우스 · 대시보드 · 콘솔 · 핸들 기둥 (어두운 실내) */
    val interior: Mesh by lazy {
        val b = MeshBuilder()
        val zs = List(91) { ZA + 0.02f + (ZR - 0.06f - ZA - 0.02f) * it / 90 }
        b.loft(zs.map { z -> val f = floorHalf(z); floatArrayOf(-f, FLOOR, z, f, FLOOR, z) }) { i -> floatArrayOf(0f, FLOOR - 1f, zs[i]) }
        for (zw in WHEEL_Z) {
            b.halfTubeX(0.60f, 0.975f, WHEEL_R, zw, 0.44f)
            b.halfTubeX(-0.60f, -0.975f, WHEEL_R, zw, 0.44f)
        }
        b.blob(0f, 0.90f, ZA + 0.30f, 0.84f, 0.13f, 0.24f, 0.3f, 0.3f, 28, 12)
        b.box(0f, 0.47f, ROW_Z[0] + 0.02f, 0.10f, 0.16f, 0.36f)
        b.beam(STEER_X, 0.93f, ZA + 0.45f, STEER_X, 1.0f, ROW_Z[0] - 0.42f, 0.05f, 0.05f)
        b.build()
    }

    /** 유리 차체 + 뒷유리 */
    val glass: Mesh by lazy {
        val b = MeshBuilder()
        val m = 22
        fun section(z: Float) = FloatArray((m + 1) * 3) { i -> archPt(z, PI * (i / 3) / m)[i % 3] }
        val zs = List(81) { ZA + (ZR - ZA) * it / 80 }
        b.loft(zs.map { section(it) }) { i -> floatArrayOf(0f, belt(zs[i]) - 0.4f, zs[i]) }
        b.fan(section(ZR), 0f, 1.0f, ZR - 1f)
        b.build()
    }

    /** 검은 필러 · 루프랙 · 카울 · 그릴 */
    val trim: Mesh by lazy {
        val b = MeshBuilder()
        val t = 0.065f
        fun beamPts(p: FloatArray, q: FloatArray, w: Float, h: Float) = b.beam(p[0], p[1], p[2], q[0], q[1], q[2], w, h)
        fun pillar(z0: Float, th0: Double, z1: Float, th1: Double) {
            beamPts(archPt(z0, th0), archPt(z1, th1), t, t)
            beamPts(archPt(z0, PI - th0), archPt(z1, PI - th1), t, t)
        }
        pillar(ZA + 0.02f, 0.0, ZR1 + 0.02f, 1.0)
        pillar(0.20f, 0.0, 0.20f, 1.05)
        pillar(1.10f, 0.0, 1.10f, 1.05)
        pillar(ZR2, 1.0, ZR - 0.01f, 0.0)
        for (th in doubleArrayOf(1.15, PI - 1.15)) {
            val p = archPt(ZR1 + 0.1f, th); val q = archPt(ZR2 - 0.1f, th)
            b.beam(p[0], p[1] + 0.035f, p[2], q[0], q[1] + 0.035f, q[2], 0.045f, 0.035f)
        }
        beamPts(archPt(ZA + 0.01f, 0.0), archPt(ZA + 0.01f, PI), 0.05f, 0.05f)
        b.box(0f, 0.50f, ZF - 0.004f, 0.36f, 0.07f, 0.012f)
        b.box(0f, 0.42f, ZR + 0.004f, 0.52f, 0.05f, 0.012f)
        b.build()
    }

    val headlight: Mesh by lazy {
        MeshBuilder().apply { beam(-0.36f, 0.72f, ZF - 0.012f, 0.36f, 0.72f, ZF - 0.012f, 0.02f, 0.035f) }.build()
    }

    val taillight: Mesh by lazy {
        val y = belt(ZR) - 0.07f
        MeshBuilder().apply { beam(-0.55f, y, ZR + 0.012f, 0.55f, y, ZR + 0.012f, 0.02f, 0.045f) }.build()
    }

    /** 대시보드 위 넓은 화면 */
    val screen: Mesh by lazy { MeshBuilder().apply { box(0.05f, 1.08f, ZA + 0.42f, 0.48f, 0.05f, 0.01f) }.build() }

    val steering: Mesh by lazy { MeshBuilder().apply { torus(0.17f, 0.022f) }.build() }

    // 바퀴 (바퀴 중심 원점, 바깥쪽 = +X)
    val tire: Mesh by lazy { MeshBuilder().apply { cylinderX(0f, 0f, 0f, WHEEL_R, 0.13f, 36) }.build() }
    val rimDisk: Mesh by lazy { MeshBuilder().apply { cylinderX(0.118f, 0f, 0f, 0.245f, 0.012f, 36) }.build() }
    val spokes: Mesh by lazy {
        val b = MeshBuilder()
        b.cylinderX(0.135f, 0f, 0f, 0.06f, 0.02f, 16)
        for (k in 0 until 5) {
            val a = 2 * PI * k / 5
            val c = cos(a).toFloat(); val s = sin(a).toFloat()
            b.beam(0.134f, 0.05f * c, 0.05f * s, 0.134f, 0.235f * c, 0.235f * s, 0.055f, 0.022f)
        }
        b.build()
    }

    /** 열린 문 (경첩 원점, 뒤쪽 +Z 로 1m) */
    val door: Mesh by lazy {
        MeshBuilder().apply {
            box(0f, 0.62f, 0.5f, 0.03f, 0.30f, 0.5f)
        }.build()
    }

    // ---- 좌석 · 사람 (좌석 원점 = 바닥, 등받이 원점 = 경첩) ----

    val seatBase: Mesh by lazy { MeshBuilder().apply { box(0f, 0.38f, 0f, 0.17f, 0.08f, 0.18f) }.build() }
    val seatCushion: Mesh by lazy { MeshBuilder().apply { blob(0f, 0.52f, 0f, 0.25f, 0.075f, 0.26f, 0.3f, 0.3f) }.build() }
    val seatBack: Mesh by lazy { MeshBuilder().apply { blob(0f, 0.33f, 0.04f, 0.25f, 0.34f, 0.075f, 0.3f, 0.3f) }.build() }
    val seatHead: Mesh by lazy { MeshBuilder().apply { blob(0f, 0.76f, 0.05f, 0.14f, 0.08f, 0.055f, 0.35f, 0.35f) }.build() }
    const val HINGE_Y = 0.55f
    const val HINGE_Z = 0.21f

    val legs: Mesh by lazy {
        MeshBuilder().apply {
            blob(0f, 0.66f, 0.10f, 0.18f, 0.09f, 0.14f, 0.8f, 0.8f)
            blob(0f, 0.63f, -0.08f, 0.17f, 0.07f, 0.21f, 0.6f, 0.6f)
            blob(0f, 0.42f, -0.36f, 0.15f, 0.17f, 0.07f, 0.6f, 0.6f)
        }.build()
    }
    val torso: Mesh by lazy { MeshBuilder().apply { blob(0f, 0.32f, -0.10f, 0.20f, 0.26f, 0.12f, 0.8f, 0.8f) }.build() }
    val head: Mesh by lazy { MeshBuilder().apply { blob(0f, 0.71f, -0.08f, 0.11f, 0.12f, 0.11f) }.build() }

    // 후방 카시트 (좌석 원점 기준). 아기 머리는 차 앞쪽, 얼굴은 뒤를 봄.
    val carrierBase: Mesh by lazy { MeshBuilder().apply { blob(0f, 0.65f, -0.02f, 0.20f, 0.06f, 0.30f, 0.3f, 0.3f) }.build() }
    val carrierShell: Mesh by lazy { MeshBuilder().apply { blob(0f, 0.75f, -0.02f, 0.22f, 0.12f, 0.31f, 0.45f, 0.5f) }.build() }
    val babyHead: Mesh by lazy { MeshBuilder().apply { blob(0f, 0.88f, -0.15f, 0.085f, 0.085f, 0.085f) }.build() }
    val blanket: Mesh by lazy { MeshBuilder().apply { blob(0f, 0.86f, 0.06f, 0.15f, 0.05f, 0.17f, 0.7f, 0.7f) }.build() }
    val handle: Mesh by lazy { MeshBuilder().apply { torus(0.20f, 0.016f, 32, 8) }.build() }
    val orb: Mesh by lazy { MeshBuilder().apply { blob(0f, 0f, 0f, 0.06f, 0.06f, 0.06f) }.build() }

    /** 그림자용 [-1,1] 사각형 */
    val unitQuad: Mesh by lazy { MeshBuilder().apply { floorQuad(-1f, -1f, 1f, 1f, 0f) }.build() }

    // ---- 도로 ----

    val ground: Mesh by lazy { MeshBuilder().apply { floorQuad(-320f, -520f, 320f, 80f, 0f) }.build() }
    val road: Mesh by lazy { MeshBuilder().apply { floorQuad(-5.6f, -520f, 5.6f, 80f, 0f) }.build() }
    val edgeLines: Mesh by lazy {
        MeshBuilder().apply {
            floorQuad(-5.35f, -520f, -5.20f, 80f, 0f)
            floorQuad(5.20f, -520f, 5.35f, 80f, 0f)
        }.build()
    }
    val dash: Mesh by lazy { MeshBuilder().apply { floorQuad(-0.075f, -1.6f, 0.075f, 1.6f, 0f) }.build() }
    val post: Mesh by lazy { MeshBuilder().apply { box(0f, 0.5f, 0f, 0.05f, 0.5f, 0.05f) }.build() }
    val reflector: Mesh by lazy { MeshBuilder().apply { box(0f, 0.86f, 0.05f, 0.055f, 0.06f, 0.012f) }.build() }
    val hills: Mesh by lazy {
        MeshBuilder().apply {
            blob(-190f, -30f, -430f, 170f, 75f, 50f, 1f, 1f, 32, 12)
            blob(-20f, -40f, -470f, 150f, 85f, 50f, 1f, 1f, 32, 12)
            blob(170f, -28f, -420f, 160f, 70f, 50f, 1f, 1f, 32, 12)
            blob(320f, -35f, -380f, 120f, 65f, 50f, 1f, 1f, 32, 12)
            blob(-330f, -35f, -360f, 120f, 60f, 50f, 1f, 1f, 32, 12)
        }.build()
    }
}
