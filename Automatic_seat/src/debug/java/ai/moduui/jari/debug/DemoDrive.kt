package ai.moduui.jari.debug

import ai.moduui.core.model.DrivingState
import ai.moduui.core.model.GeoPoint
import ai.moduui.jari.control.TripController
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/** 시연 지도에 보여 줄 진행 상황 */
data class DemoProgress(
    val km: Double,
    val totalKm: Double,
    val kmh: Double,
    val destination: String?,
    /** 들르기로 한 곳 (경유지) */
    val stopName: String?,
    val stopKm: Double?,
    /** 경유지에 서 있음 (P단). D 로 바꾸면 다시 달림 */
    val stopped: Boolean,
    val arrived: Boolean,
)

/**
 * 시연 전용 (debug): 실제 도로 경로를 따라 차를 움직임.
 * Pleos 에뮬레이터 내비는 GPS(NMEA 체크섬)를 버려서 위치가 고정이므로, 앱이 위치 · 남은 시간을 대신 흉내 냄.
 *
 *   adb shell am broadcast ... --es cmd demoDrive [--es kmh 100] [--es x 30] [--es from 0]
 *   adb shell am broadcast ... --es cmd demoDrive --es stop true
 *
 * - 차가 D단일 때만 움직임 (가상 콘솔 기어). 속도는 콘솔 속도, 0 이면 kmh.
 * - 경유지를 고르면([경유]) 그 휴게소에서 멈추고 콘솔 기어를 P 로 바꿈 → D 로 바꾸면 다시 출발.
 * - 끝까지 가면 도착(arrive). 좌석 · 안전 장치와는 무관.
 */
object DemoDrive {
    private val _progress = MutableStateFlow<DemoProgress?>(null)
    val progress: StateFlow<DemoProgress?> = _progress

    private var job: Job? = null
    private var stopName: String? = null
    private var stopKm: Double? = null
    /** 고른 경유지 (경로 위 거리로는 주행 루프가 자기 경로로 바꿈 — 고른 순간에는 내비가 다시 찾는 중이라 경로가 없음) */
    private var pendingStop: Pair<String, GeoPoint>? = null
    private var stopped = false

    /** 시연 배속 (차 이동 · 앱 시계) */
    const val SPEEDUP = 30.0 // 11/15 사용자: 10배 → 30배 (90배는 너무 빨라 되돌림)

    fun start(scope: CoroutineScope, c: TripController, kmh: Double, speedup: Double, fromKm: Double) {
        stop(c, clear = false)
        stopName = null
        stopKm = null
        pendingStop = null
        stopped = false
        job = scope.launch {
            var path: ai.moduui.core.route.RoutePath? = null
            var name: String? = null
            var km = fromKm
            var finished: ai.moduui.core.route.RoutePath? = null
            while (isActive) {
                // 목적지가 바뀌면 (새 경로 객체) 처음부터. 경유지를 넣을 때는 같은 경로가 유지됨
                val p = c.currentRoadPath()
                if (p == null || p === finished) {
                    // 경로가 없어졌으면(도착 · 취소) 같은 목적지로 다시 정해도 처음부터 달릴 수 있게
                    if (p == null) finished = null
                    delay(TICK_MS)
                    continue
                }
                if (p !== path) {
                    if (path != null) km = 0.0
                    path = p
                    name = c.ui.value.navi.route?.destinationName
                    km = km.coerceIn(0.0, p.lengthKm)
                    stopName = null
                    stopKm = null
                    stopped = false
                    Log.i(TAG, "drive on new road ${"%.1f".format(p.lengthKm)}km")
                }
                pendingStop?.let { (n, at) ->
                    pendingStop = null
                    val (along, off) = p.locate(at)
                    if (off <= 2.0 && along > km) {
                        stopName = n
                        stopKm = along
                        Log.i(TAG, "stop at $n ${"%.1f".format(along)}km (now ${"%.1f".format(km)}km)")
                    } else {
                        Log.w(TAG, "stop $n not ahead on road (along ${"%.1f".format(along)} off ${"%.1f".format(off)} now ${"%.1f".format(km)})")
                    }
                }
                val ui = c.ui.value
                val inDrive = ui.driving.state == DrivingState.DRIVING || ui.driving.gear == "D"
                val v = ui.driving.speedKmh?.toDouble()?.takeIf { it > 1 } ?: kmh
                if (stopped) {
                    // 휴게소에 서 있다가 D 로 바꾸면 다시 출발
                    if (inDrive) {
                        stopped = false
                        stopName = null
                        stopKm = null
                    }
                }
                // 달리는 동안만 앱 시계도 같은 배속 (서 있을 때는 보통 시계)
                c.setTimeSpeed(if (inDrive && !stopped) speedup.toInt().coerceAtLeast(1) else 1)
                if (!stopped && inDrive) {
                    // 짧게 자주 움직여 지도가 자연스럽게 따라감
                    km += v * speedup / 3600.0 * (TICK_MS / 1000.0)
                }
                val target = stopKm
                if (!stopped && target != null && km >= target) {
                    km = target
                    stopped = true
                    launch(Dispatchers.IO) { Console.park() }
                    c.simulateWaypointArrived(stopName)
                }
                km = km.coerceAtMost(p.lengthKm)
                val remainSec = ((p.lengthKm - km) / kmh * 3600).toLong()
                c.simulatePosition(km, remainSec)
                val done = km >= p.lengthKm
                _progress.value = DemoProgress(km, p.lengthKm, if (stopped || !inDrive) 0.0 else v, name, stopName, stopKm, stopped, done)
                if (done) {
                    c.simulatePosition(null)
                    c.simulateArrival(name)
                    launch(Dispatchers.IO) { Console.park() }
                    // 이 경로는 끝. 새 목적지를 정하면 다시 달림
                    finished = p
                    path = null
                    km = 0.0
                }
                delay(TICK_MS)
            }
        }
    }

    /** 경유지로 고른 곳에서 세움 (경로에서 2km 안이고 앞쪽일 때만, 다음 주행 틱에서 정함) */
    @Suppress("UNUSED_PARAMETER")
    fun stopAt(c: TripController, name: String, at: GeoPoint) {
        pendingStop = name to at
    }

    fun stop(c: TripController, clear: Boolean = true) {
        job?.cancel()
        job = null
        c.setTimeSpeed(1)
        if (clear) {
            c.simulatePosition(null)
            _progress.value = null
        }
    }

    private const val TAG = "DemoDrive"
    /** 움직이는 간격 (ms). 짧을수록 부드러움 */
    private const val TICK_MS = 250L
}

/** 같은 에뮬레이터의 Pleos Virtual Console (debug 전용). 세션을 먼저 보내야 쓰기가 먹힘 */
private object Console {
    private const val BASE = "http://127.0.0.1:8088"

    fun park() {
        req("POST", "$BASE/browser/session?client=everyseat-sim")
        req("PUT", "$BASE/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0")
        req("PUT", "$BASE/vehicle/gear?gear=Park")
    }

    private fun req(method: String, url: String) = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 800
        c.readTimeout = 800
        try { c.responseCode } finally { c.disconnect() }
    }.onFailure { Log.w("DemoDrive", "console $method failed", it) }
}
