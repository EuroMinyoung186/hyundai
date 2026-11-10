package ai.moduui.jari.vehicle

import ai.moduui.core.model.SeatId
import ai.moduui.core.seat.SeatAxis
import ai.moduui.jari.BuildConfig
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 에뮬레이터 시연 전용 (디버그 빌드 + 에뮬레이터에서만 만들어짐, 실차·출시 빌드에는 없음).
 *
 * 에뮬레이터에서는 SDK 로 좌석을 움직이면 실제로 움직이지만, SDK 위치 읽기 값은 그대로임.
 * 그 값을 믿으면 "이미 맞는 위치"·"위치 변화 없음" 처럼 사실과 다르게 나오므로,
 * 같은 에뮬레이터 안의 Virtual Console 에서 위치를 읽음. 쓰기는 주행 중 시연용 [nudge] 뿐임.
 */
class ConsoleSeatPositions private constructor() {

    suspend fun readAll(): Map<Pair<SeatId, SeatAxis>, Int>? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(STATE_URL).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            try {
                val positions = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    .getJSONObject("seat").getJSONObject("positions")
                val slide = positions.getJSONObject("slide")
                val recline = positions.getJSONObject("recline")
                buildMap {
                    for ((area, seat) in AREAS) {
                        put(seat to SeatAxis.FORE_AFT, slide.getInt(area))
                        put(seat to SeatAxis.BACKREST, recline.getInt(area))
                    }
                }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    suspend fun read(seat: SeatId, axis: SeatAxis): Int? = readAll()?.get(seat to axis)

    /**
     * 주행 중 소폭 조절 시연용. 차량 서비스가 P단이 아니면 SDK 좌석 명령을 거부하므로 콘솔 값을 직접 바꿈.
     * 실제 좌석 명령이 아니어서 화면에도 "시연"으로 표시함.
     */
    suspend fun nudge(seat: SeatId, axis: SeatAxis, deltaPercent: Int): Boolean = withContext(Dispatchers.IO) {
        val area = AREAS.firstOrNull { it.second == seat }?.first ?: return@withContext false
        val now = read(seat, axis) ?: return@withContext false
        val control = if (axis == SeatAxis.FORE_AFT) "slide" else "recline"
        val value = (now + deltaPercent).coerceIn(RANGE)
        request("POST", "$BASE_URL/browser/session?client=$SESSION_CLIENT") // 콘솔은 세션 없이 쓰기를 거부함
        request("PUT", "$BASE_URL/vehicle/seat?kind=position&control=$control&area=$area&value=$value")
    }

    /**
     * 콘솔을 "사용 중"으로 유지. 콘솔은 몇 초 동안 세션이 없으면 비활성이 되고, 그동안은 SDK 좌석 명령을 받아도
     * 좌석이 움직이지 않음. 시뮬레이터 스크립트 없이 휴대폰 앱만으로 시연할 때도 좌석이 움직이도록 앱이 직접 유지함.
     */
    suspend fun keepAlive(): Boolean = withContext(Dispatchers.IO) {
        request("POST", "$BASE_URL/browser/session?client=$SESSION_CLIENT")
    }

    private fun request(method: String, url: String): Boolean = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        try {
            c.responseCode in 200..299
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    companion object {
        /** 콘솔 값의 범위. 앞뒤는 100 이 맨 앞, 등받이는 100 이 가장 세운 쪽. */
        val RANGE = 0..100

        private const val BASE_URL = "http://127.0.0.1:8088"
        private const val STATE_URL = "$BASE_URL/vehicle/state"
        private const val TIMEOUT_MS = 500

        /** everyseat-sim.ps1 과 같은 이름: 다른 이름을 쓰면 시뮬레이터가 콘솔 탭이 열린 것으로 오해함 */
        private const val SESSION_CLIENT = "everyseat-sim"
        private val AREAS = listOf(
            "driver" to SeatId.R1_LEFT,
            "passenger" to SeatId.R1_RIGHT,
            "rear_left" to SeatId.R2_LEFT,
            "rear_right" to SeatId.R2_RIGHT,
        )

        fun ifAvailable(): ConsoleSeatPositions? =
            if (BuildConfig.DEBUG && Build.HARDWARE == "ranchu") ConsoleSeatPositions() else null
    }
}
