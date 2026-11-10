package ai.moduui.jari.phone

import ai.moduui.core.model.BabyState
import ai.moduui.core.model.LuggageType
import ai.moduui.core.model.StateSource
import ai.moduui.core.model.TripRequest
import ai.moduui.jari.control.PhoneStatus
import ai.moduui.jari.control.StepOutcome
import ai.moduui.jari.control.TripController
import ai.moduui.jari.control.UiState
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.format.DateTimeFormatter

/**
 * 보호자 휴대폰 앱(모두의 자리 Phone)과 차 앱을 잇는 연결.
 *
 * 휴대폰과 차는 직접 붙지 않고 중계 서버를 거침 (실서비스에서는 팀 서버, 시연에서는 PC 의
 * tools/sim/phone-relay.py). 차는 0.7초마다 받은 편지함을 확인하고, 자기 상태를 서버에 올림.
 *  - 연결: 차 화면에 6자리 코드를 보여 주고, 휴대폰이 같은 코드를 입력하면 연결됨 (디자인 P-2 · C-1a)
 *  - 휴대폰 → 차: trip(인원·짐) · prepare(출발 준비) · baby(아기 상태) · feed · restRequest · rest(경유하기)
 *  - 차 → 휴대폰: 세팅 계획 · 좌석 진행 · 아기 상태 · 다음 수유 · 휴게소 제안 (디자인 P-4 · P-8)
 */
class PhoneLink(
    private val baseUrl: String,
    private val controller: TripController,
    private val scope: CoroutineScope,
    prefs: SharedPreferences,
) {
    /** 차마다 고정된 연결 코드 (앱을 다시 켜도 같아서 휴대폰을 다시 연결하지 않아도 됨) */
    private val code: String = prefs.getString(KEY_CODE, null) ?: (100_000..999_999).random().toString().also {
        prefs.edit().putString(KEY_CODE, it).apply()
    }
    private var after = 0L
    private var caughtUp = false
    private var lastStatus: String? = null
    private var lastStatusAt = 0L

    fun start() {
        controller.setPhone(PhoneStatus(code = code))
        scope.launch {
            while (isActive) {
                val reply = get("/car/inbox?code=$code&after=$after")
                if (reply == null) {
                    controller.setPhone(PhoneStatus(code = code, connected = false, serverReachable = false))
                    delay(2_000)
                    continue
                }
                val connected = reply.optBoolean("connected")
                controller.setPhone(PhoneStatus(code = code, connected = connected, serverReachable = true))
                val messages = reply.optJSONArray("messages") ?: JSONArray()
                if (!caughtUp) {
                    // 앱을 켜기 전에 와 있던 명령은 실행하지 않음 (다시 켰을 때 지난 [출발 준비]로 좌석이 움직이면 위험)
                    caughtUp = true
                    after = maxOf(after, reply.optLong("latest", 0))
                    if (messages.length() > 0) Log.i(TAG, "skipped ${messages.length()} old messages")
                } else {
                    for (i in 0 until messages.length()) {
                        val m = messages.getJSONObject(i)
                        after = maxOf(after, m.optLong("seq"))
                        val age = m.optDouble("age", 0.0)
                        if (age > MAX_AGE_SEC) {
                            Log.w(TAG, "skipped stale ${m.optString("type")} (${age}s old)")
                            continue
                        }
                        runCatching { handle(m) }.onFailure { Log.w(TAG, "message failed: ${it.message}") }
                    }
                }
                pushStatus()
                delay(POLL_MS)
            }
        }
    }

    private fun handle(m: JSONObject) {
        val type = m.optString("type")
        Log.i(TAG, "from phone: $type")
        fun n(key: String) = m.optInt(key, 0).coerceIn(0, 6)
        when (type) {
            "trip" -> controller.planTrip(
                TripRequest(
                    destinationName = m.optString("destination").ifBlank { null },
                    seniors = n("seniors"),
                    adults = n("adults"),
                    children = n("children"),
                    infants = n("infants").coerceAtMost(3),
                    luggage = mapOf(
                        LuggageType.STROLLER to n("strollers"),
                        LuggageType.SUITCASE to n("suitcases"),
                        LuggageType.BABY_BAG to n("babyBags"),
                    ).filterValues { it > 0 },
                    caregiverNextToBaby = m.optBoolean("caregiverNextToBaby", true),
                ),
                "휴대폰",
            )
            "prepare" -> {
                controller.confirmDeparture()
                controller.showBoarding() // 좌석이 움직이는 모습을 차 화면에서 볼 수 있게
            }
            "baby" -> {
                val state = runCatching { BabyState.valueOf(m.optString("state")) }.getOrNull() ?: return
                controller.reportBaby(state, StateSource.PHONE)
            }
            "feed" -> controller.logFeed()
            "restRequest" -> controller.requestRestStops(nursing = m.optBoolean("nursing", true))
            "rest" -> scope.launch { controller.addRestStop(m.optInt("index", 0)) }
            else -> Log.w(TAG, "unknown message $type")
        }
    }

    private suspend fun pushStatus() {
        val body = statusJson(controller.ui.value).toString()
        val now = System.currentTimeMillis()
        if (body == lastStatus && now - lastStatusAt < 3_000) return
        if (post("/car/status?code=$code", body)) {
            lastStatus = body
            lastStatusAt = now
        }
    }

    private fun statusJson(ui: UiState) = JSONObject().apply {
        put("gear", ui.driving.gear ?: "")
        put("speed", ui.driving.speedKmh?.toInt() ?: 0)
        put("driving", ui.isDriving)
        put("departed", ui.departed)
        put("arrived", ui.arrived)
        ui.trip?.let { t ->
            put(
                "trip",
                JSONObject()
                    .put("destination", t.destinationName ?: "")
                    .put("seniors", t.seniors).put("adults", t.adults).put("children", t.children).put("infants", t.infants)
                    .put("strollers", t.luggage[LuggageType.STROLLER] ?: 0)
                    .put("suitcases", t.luggage[LuggageType.SUITCASE] ?: 0)
                    .put("babyBags", t.luggage[LuggageType.BABY_BAG] ?: 0)
                    .put("source", ui.tripSource ?: ""),
            )
        }
        ui.plan?.let { p ->
            put(
                "plan",
                JSONObject()
                    .put("summary", p.summaryKo)
                    .put("sdk", p.sdkSteps.size)
                    .put("guide", p.guideSteps.size)
                    .put("notes", JSONArray(p.notesKo)),
            )
        }
        ui.run?.let { r ->
            put(
                "run",
                JSONObject()
                    .put("reason", r.reasonKo)
                    .put("done", r.steps.count { it.outcome.terminal })
                    .put("total", r.steps.size)
                    .put("countdown", r.countdown ?: -1)
                    .put("finished", r.finished)
                    .put("allOk", r.steps.all { it.outcome in OK_OUTCOMES })
                    .put(
                        "steps",
                        JSONArray(
                            r.steps.map {
                                JSONObject()
                                    .put("label", "${it.step.seat.labelKo} ${it.step.action.labelKo}")
                                    .put("outcome", it.outcome.labelKo)
                                    .put("terminal", it.outcome.terminal)
                                    .put("ok", it.outcome in OK_OUTCOMES)
                            },
                        ),
                    ),
            )
        }
        put("pending", ui.pendingSteps.size)
        put(
            "baby",
            JSONObject()
                .put("state", ui.baby.name)
                .put("label", ui.baby.labelKo)
                .put("minutes", ui.babyMinutes ?: -1)
                .put("source", ui.babySource.labelKo),
        )
        ui.minutesToFeed?.let { put("minutesToFeed", it) }
        ui.lastFeedAt?.let { put("lastFeed", it.format(HM)) }
        ui.restStops?.let { o ->
            put(
                "rest",
                JSONObject()
                    .put("reason", o.reasonKo)
                    .put("nursing", o.nursing)
                    .put("added", o.addedName ?: "")
                    .put(
                        "candidates",
                        JSONArray(
                            o.candidates.map { c ->
                                JSONObject()
                                    .put("name", c.restArea.name)
                                    .put("km", c.distanceKm)
                                    .put("eta", c.etaMinutes)
                                    .put("nursingRoom", c.restArea.hasNursingRoom)
                            },
                        ),
                    ),
            )
        }
        put("comfort", JSONArray(ui.comfort.filter { it.applied }.map { it.labelKo + if (it.simulated) " (시연)" else "" }))
        put("quiet", ui.quiet)
        ui.lastVoice?.let { put("voice", it) }
        ui.navi.route?.let { r -> put("remainMin", r.remainSeconds / 60) }
        ui.eta?.let { put("babyEtaMin", it.predictedTotalMinutes) }
        put("rearAlert", ui.rearSeatAlert)
    }

    private suspend fun get(path: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(baseUrl + path).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            try {
                if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().readText())
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    private suspend fun post(path: String, body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(baseUrl + path).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            try {
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                c.responseCode == 200
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "ModuuiPhone"
        private const val KEY_CODE = "phone_link_code"
        /** 이보다 오래된 휴대폰 명령은 실행하지 않음 (연결이 끊겼다 붙었을 때 늦게 도착한 명령) */
        private const val MAX_AGE_SEC = 20.0
        /** 휴대폰에 "준비됨"으로 보여도 되는 결과. 문 열림 · 건너뜀 · 실패 · 취소는 준비된 것이 아님. */
        private val OK_OUTCOMES = setOf(StepOutcome.DONE, StepOutcome.SENT, StepOutcome.GUIDE, StepOutcome.SIMULATED, StepOutcome.UNSUPPORTED)
        private const val POLL_MS = 700L
        private const val TIMEOUT_MS = 1_500
        private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
