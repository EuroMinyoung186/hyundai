package ai.moduui.jari.debug

import ai.moduui.jari.vehicle.ConsoleSeatPositions
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.MoveControl
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 전동 좌석 지원 확인 (디버그 전용). Vehicle SDK 의 공개 API 만 씀.
 *
 * 축마다 "움직일 수 있는 좌석" 목록:
 *  adb shell am broadcast -a ai.moduui.jari.debug.CMD -n ai.moduui.jari/.debug.DebugCommandReceiver --es cmd seatCaps
 * 좌석 하나를 실제로 움직여 봄 (P단에서만 됨):
 *  ... --es cmd seatMove --es area ROW_2_RIGHT --es axis ForeAft --es dir BACKWARD --es ms 1500
 *
 * 결과는 logcat 태그 SeatProbe.
 */
object SeatProbe {
    private const val TAG = "SeatProbe"

    private typealias Check = Seat.((CapabilityConfig, List<SeatArea>) -> Unit, (Exception) -> Unit) -> Unit
    private typealias Move = Seat.(SeatArea, MoveControl, (SeatArea, MoveControl?) -> Unit, (Exception) -> Unit) -> Unit

    private class Axis(val name: String, val check: Check, val move: Move)

    private val AXES = listOf(
        Axis("ForeAft", { ok, err -> checkMoveSeatForeAftCapabilities(ok, err) }, { a, c, ok, err -> moveSeatForeAft(a, c, ok, err) }),
        Axis("Backrest", { ok, err -> checkMoveSeatBackrestAngleCapabilities(ok, err) }, { a, c, ok, err -> moveSeatBackrestAngle(a, c, ok, err) }),
        Axis("Height", { ok, err -> checkMoveSeatHeightCapabilities(ok, err) }, { a, c, ok, err -> moveSeatHeight(a, c, ok, err) }),
        Axis("Tilt", { ok, err -> checkMoveSeatTiltCapabilities(ok, err) }, { a, c, ok, err -> moveSeatTilt(a, c, ok, err) }),
        Axis("Depth", { ok, err -> checkMoveSeatDepthCapabilities(ok, err) }, { a, c, ok, err -> moveSeatDepth(a, c, ok, err) }),
        Axis("HeadrestHeight", { ok, err -> checkMoveSeatHeadrestHeightCapabilities(ok, err) }, { a, c, ok, err -> moveSeatHeadrestHeight(a, c, ok, err) }),
        Axis("HeadrestAngle", { ok, err -> checkMoveSeatHeadrestAngleCapabilities(ok, err) }, { a, c, ok, err -> moveSeatHeadrestAngle(a, c, ok, err) }),
        Axis("HeadrestForeAft", { ok, err -> checkMoveSeatHeadrestForeAftCapabilities(ok, err) }, { a, c, ok, err -> moveSeatHeadrestForeAft(a, c, ok, err) }),
        Axis("LumbarForeAft", { ok, err -> checkMoveSeatLumbarForeAftCapabilities(ok, err) }, { a, c, ok, err -> moveSeatLumbarForeAft(a, c, ok, err) }),
        Axis("LumbarSide", { ok, err -> checkMoveSeatLumbarSideSupportCapabilities(ok, err) }, { a, c, ok, err -> moveSeatLumbarSideSupport(a, c, ok, err) }),
        Axis("BeltHeight", { ok, err -> checkMoveSeatBeltHeightCapabilities(ok, err) }, { a, c, ok, err -> moveSeatBeltHeight(a, c, ok, err) }),
    )

    private fun <T> withSeat(ctx: Context, block: suspend (Seat) -> T) {
        CoroutineScope(Dispatchers.Default).launch {
            val v = Vehicle(ctx)
            try {
                v.initialize()
                block(v.getSeat())
            } catch (e: Exception) {
                Log.e(TAG, "probe failed", e)
            } finally {
                runCatching { v.release() }
            }
        }
    }

    fun caps(ctx: Context) = withSeat(ctx) { seat ->
        val rearOk = mutableListOf<String>()
        for (axis in AXES) {
            val r = withTimeoutOrNull(3_000) {
                suspendCancellableCoroutine { cont ->
                    seat.(axis.check)(
                        { cfg, areas -> if (cont.isActive) cont.resume("access=${cfg.accessType} areas=${areas.flatMap { it.values.map(SeatValue::name) }}") },
                        { e -> if (cont.isActive) cont.resume("FAILED ${e.javaClass.simpleName}: ${e.message}") },
                    )
                }
            } ?: "TIMEOUT"
            Log.i(TAG, "MOVE ${axis.name.padEnd(16)} $r")
            if ("ROW_2" in r || "ROW_3" in r) rearOk += axis.name
        }
        Log.i(TAG, "REAR (2·3열) 전동 이동 가능 축: ${if (rearOk.isEmpty()) "없음" else rearOk.joinToString()}")
    }

    fun move(ctx: Context, areaName: String?, axisName: String?, dirName: String?, ms: Long) = withSeat(ctx) { seat ->
        val area = SeatArea(SeatValue.valueOf(areaName ?: "ROW_2_RIGHT"))
        val axis = AXES.firstOrNull { it.name.equals(axisName ?: "ForeAft", ignoreCase = true) } ?: error("axis? ${AXES.map { it.name }}")
        val dir = MoveControl.valueOf(dirName ?: "BACKWARD")
        val console = ConsoleSeatPositions.ifAvailable()
        console?.keepAlive()
        Log.i(TAG, "before console=${console?.readAll()}")
        suspend fun send(c: MoveControl) = withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                seat.(axis.move)(
                    area, c,
                    { _, _ -> if (cont.isActive) cont.resume("ok") },
                    { e -> if (cont.isActive) cont.resume("FAILED ${e.javaClass.simpleName}: ${e.message}") },
                )
            }
        } ?: "TIMEOUT"
        Log.i(TAG, "move ${axis.name} ${area.values.joinToString()} $dir -> ${send(dir)}")
        var left = ms
        while (left > 0) { // 콘솔이 비활성이 되지 않도록 사이사이 세션 유지
            console?.keepAlive()
            delay(minOf(left, 1_000))
            left -= 1_000
        }
        Log.i(TAG, "move ${axis.name} STOP -> ${send(MoveControl.STOP)}")
        delay(500)
        Log.i(TAG, "after  console=${console?.readAll()}")
    }
}
