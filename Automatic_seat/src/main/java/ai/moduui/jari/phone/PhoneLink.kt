package ai.moduui.jari.phone

import ai.moduui.core.model.BabyState
import ai.moduui.core.model.CarSeatDirection
import ai.moduui.core.model.LuggageType
import ai.moduui.core.model.PlannedKind
import ai.moduui.core.model.PlannedSeat
import ai.moduui.core.model.SeatId
import ai.moduui.core.seat.SeatAxis
import ai.moduui.core.seat.SeatPlanner
import ai.moduui.core.seat.TrunkEstimator
import ai.moduui.core.model.StateSource
import ai.moduui.core.model.TripRequest
import ai.moduui.jari.navi.RoadAhead
import ai.moduui.jari.control.CareAct
import ai.moduui.jari.control.PhoneStatus
import ai.moduui.jari.control.StepOutcome
import ai.moduui.jari.control.TripController
import ai.moduui.jari.control.UiState
import ai.moduui.jari.control.seatViews
import ai.moduui.jari.control.trunkEstimate
import ai.moduui.jari.vehicle.SeatFeature
import ai.moduui.jari.vehicle.Support
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
 * 보호자 휴대폰 앱(Baby Seat Phone)과 차 앱을 잇는 연결.
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
                // 중계 서버가 다시 켜지면 메시지 번호가 1부터 다시 시작함 → 예전 번호로 물으면 새 메시지를 영영 못 받음
                // (11/15 확인: "차가 좌석을 못 받고 있대"). 서버의 마지막 번호가 우리 번호보다 작으면 처음부터 다시 받음
                val latest = reply.optLong("latest", 0)
                if (caughtUp && latest < after) {
                    Log.i(TAG, "relay restarted (latest $latest < after $after), reading from the start")
                    after = 0
                    continue
                }
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
            "trip" -> {
                // 선택 키 seatNeeds: 넣은 아이들의 카시트 단계. 뒤보기 · 카시트 → infants, 부스터 → children.
                // 개수는 휴대폰이 보낸 수와 비교해 큰 쪽을 씀 (카시트 좌석이 모자라게 잡히지 않도록)
                val needs = m.optJSONArray("seatNeeds")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
                val needInfants = needs.count { it == "REAR_SEAT" || it == "CAR_SEAT" }
                val needChildren = needs.count { it == "BOOSTER" }
                controller.planTrip(
                    TripRequest(
                        destinationName = m.optString("destination").ifBlank { null },
                        seniors = n("seniors"),
                        adults = n("adults"),
                        children = maxOf(n("children"), needChildren.coerceAtMost(6)),
                        infants = maxOf(n("infants"), needInfants).coerceAtMost(3),
                        luggage = mapOf(
                            LuggageType.STROLLER to n("strollers"),
                            LuggageType.SUITCASE to n("suitcases"),
                            LuggageType.BABY_BAG to n("babyBags"),
                        ).filterValues { it > 0 },
                        caregiverNextToBaby = m.optBoolean("caregiverNextToBaby", true),
                        measuredLuggageLiters = if (m.has("luggageLiters")) m.optInt("luggageLiters").coerceIn(0, 5_000) else null,
                        rearFacingSeat = "REAR_SEAT" in needs,
                        seatPlan = seatPlanOf(m),
                    ),
                    "휴대폰",
                )
            }
            // 휴대폰 첫 설정의 아기 정보. 차 아기 화면(애칭 · 나이 · 카시트 방향 · 수유 간격)에 채움.
            // 카시트 기울기(°)는 차의 '주행 중 등받이 허용 폭(%)'과 뜻이 달라 쓰지 않음
            "babyProfile" -> {
                val cur = controller.ui.value.profile
                val dir = when (m.optString("carSeatDir")) {
                    "REAR" -> CarSeatDirection.REAR_FACING
                    "FRONT" -> CarSeatDirection.FORWARD_FACING
                    "BOOSTER" -> CarSeatDirection.BOOSTER
                    else -> cur.seatDirection
                }
                controller.updateProfile(
                    cur.copy(
                        nickname = m.optString("name").trim().take(10).ifBlank { cur.nickname },
                        ageMonths = if (m.has("ageMonths")) m.optInt("ageMonths").coerceIn(0, 144) else cur.ageMonths,
                        seatDirection = dir,
                        feedIntervalMin = if (m.has("feedEveryMin")) m.optInt("feedEveryMin").coerceIn(30, 600) else cur.feedIntervalMin,
                        // 11/15 추가: 아기 밥(이유식) 시간 ["12:00","18:00"]. 키가 없으면 그대로
                        mealTimes = m.optJSONArray("mealTimes")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i -> runCatching { java.time.LocalTime.parse(arr.getString(i)) }.getOrNull() }.distinct().sorted().take(4)
                        } ?: cur.mealTimes,
                    ),
                )
            }
            "prepare" -> {
                controller.confirmDeparture()
                controller.showBoarding() // 좌석이 움직이는 모습을 차 화면에서 볼 수 있게
            }
            // 11/14 확장: state 는 없을 수 있음 ("똥 쌌어요" 같은 보호자 버튼). label · emoji · actions[{do, value?}]
            "baby" -> {
                val state = runCatching { BabyState.valueOf(m.optString("state")) }.getOrNull()
                val acts = m.optJSONArray("actions")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val a = arr.optJSONObject(i) ?: return@mapNotNull null
                        val kind = a.optString("do").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        CareAct(kind, a.optString("value").takeIf { it.isNotBlank() })
                    }
                }.orEmpty()
                val label = m.optString("label").takeIf { it.isNotBlank() }
                if (label == null && acts.isEmpty()) {
                    state?.let { controller.reportBaby(it, StateSource.PHONE) }
                } else {
                    controller.onPhoneBaby(state, label, m.optString("emoji").takeIf { it.isNotBlank() }, acts)
                }
            }
            // minutesAgo(선택, 11/15 추가): 휴대폰에서 고른 "○분 전 수유". 없으면 지금
            "feed" -> controller.logFeedAgo(m.optInt("minutesAgo", 0))
            "restRequest" -> controller.requestRestStops(nursing = m.optBoolean("nursing", true))
            "rest" -> scope.launch { controller.addRestStop(m.optInt("index", 0)) }
            // 휴대폰 3D 시뮬에서 좌석 하나를 직접 맞춤. 움직이는 건 차의 SeatStepRunner (카운트다운 · P단 · 빈 좌석 · 문)
            "seatAdjust" -> {
                val seat = runCatching { SeatId.valueOf(m.optString("seat")) }.getOrNull() ?: return
                fun pct(key: String) = if (m.has(key)) m.optInt(key).coerceIn(0, 100) else null
                controller.adjustSeat(seat, pct("foreAft"), pct("backrest"), "휴대폰")
                controller.showBoarding()
            }
            else -> Log.w(TAG, "unknown message $type")
        }
    }

    /**
     * 선택 키 seatPlan: 보호자가 휴대폰 2D 에서 고른 자리 + AI 자세.
     * [{seat:"R2_RIGHT", label:"이봄", who:"BABY", need?:"CAR_SEAT", foreAft?:0..100, backrest?:0..100}]
     */
    private fun seatPlanOf(m: JSONObject): List<PlannedSeat> {
        val a = m.optJSONArray("seatPlan") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val seat = runCatching { SeatId.valueOf(o.optString("seat")) }.getOrNull() ?: return@mapNotNull null
            val need = o.optString("need")
            val kind = when {
                need == "REAR_SEAT" || need == "CAR_SEAT" -> PlannedKind.CAR_SEAT
                need == "BOOSTER" -> PlannedKind.BOOSTER
                o.optString("who") == "SENIOR" -> PlannedKind.SENIOR
                else -> PlannedKind.ADULT
            }
            fun pct(key: String) = if (o.has(key)) o.optInt(key).coerceIn(0, 100) else null
            PlannedSeat(seat, o.optString("label").ifBlank { seat.labelKo }.take(12), kind, pct("foreAft"), pct("backrest"))
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
        // 11/13 추가 (키 추가만): 내비 목적지 · 경로 거리 · 앞길 안내. 휴대폰 여정 기억(거리 비교)과 주행 화면에 씀
        val navi = ui.navi
        (navi.route?.destinationName ?: RoadAhead.destinationLeg(navi)?.name)?.takeIf { it.isNotBlank() }?.let { put("naviDestination", it) }
        (RoadAhead.destinationLeg(navi)?.meters?.takeIf { it > 0 } ?: navi.route?.remainMeters?.toInt())?.let { put("routeKm", Math.round(it / 100.0) / 10.0) }
        if (navi.guiding || navi.nextTurn != null || navi.speedLimit != null) {
            put(
                "road",
                JSONObject().apply {
                    navi.speedLimit?.let { put("speedLimit", it) }
                    navi.nextTurn?.let { put("turn", RoadAhead.turnKo(it.type)).put("turnM", it.meters).put("turnText", it.description) }
                    navi.cautions.firstOrNull { RoadAhead.cautionKo(it.type) != null }?.let { put("caution", RoadAhead.cautionKo(it.type)).put("cautionM", it.meters) }
                    RoadAhead.nextWaypoint(navi)?.let { put("waypoint", it.name).put("waypointM", it.meters).put("waypointMin", (it.seconds + 59) / 60) }
                    RoadAhead.advice(navi, ui.baby == ai.moduui.core.model.BabyState.ASLEEP, ui.profile.nickname)?.let { put("advice", it.text).put("urgent", it.urgent) }
                },
            )
        }
        ui.trip?.let { t ->
            put(
                "trip",
                JSONObject()
                    .put("destination", t.destinationName ?: "")
                    .put("seniors", t.seniors).put("adults", t.adults).put("children", t.children).put("infants", t.infants)
                    .put("strollers", t.luggage[LuggageType.STROLLER] ?: 0)
                    .put("suitcases", t.luggage[LuggageType.SUITCASE] ?: 0)
                    .put("babyBags", t.luggage[LuggageType.BABY_BAG] ?: 0)
                    .put("source", ui.tripSource ?: "")
                    .apply { t.measuredLuggageLiters?.let { put("luggageLiters", it) } },
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
        // 11/14 추가: 출발 알림에서 정한 다음 수유 · 밥 시간 (HH:mm)
        ui.nextFeedAt?.let { put("nextFeedAt", it.format(HM)) }
        ui.mealAt?.let { put("mealAt", it.format(HM)) }
        ui.minutesToMeal?.let { put("minutesToMeal", it) }
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
        put("seats", seatsJson(ui))
        put("trunk", trunkJson(ui))
    }

    /**
     * 휴대폰 3D 용 트렁크 어림값. SDK 에 트렁크 API 가 없어서 좌석 줄 수 · 마지막 줄 좌석 위치 ·
     * 좌석 계획의 3열 접힘 · 짐 부피로 어림함 (TrunkEstimator).
     */
    private fun trunkJson(ui: UiState): JSONObject {
        val t = trunkEstimate(ui)
        return JSONObject()
            .put("liters", t.liters)
            .put("used", t.usedLiters)
            .put("rows", t.rows)
            .put("slide", t.slideLiters)
            .put("folded", JSONArray(t.folded.map { it.name }))
            .put("basis", t.basisKo)
    }

    /**
     * 휴대폰 3D 용 좌석 상황: 이 차에 있는 좌석, 움직일 수 있는 축, 지금 위치(0..100), 착좌.
     * 위치는 실차 SDK(getCurrentSeat*Position) 또는 에뮬레이터 콘솔에서 읽은 값. 모르면 넣지 않음.
     */
    private fun seatsJson(ui: UiState) = JSONArray().apply {
        for (s in seatViews(ui)) {
            put(
                JSONObject()
                    .put("id", s.id.name)
                    .put("row", s.id.row)
                    .put("side", s.id.side.name)
                    .put("label", s.id.labelKo)
                    .put("foreAftMove", s.foreAftMove)
                    .put("backrestMove", s.backrestMove)
                    .apply {
                        s.foreAft?.let { put("foreAft", it) }
                        s.backrest?.let { put("backrest", it) }
                        s.occupied?.let { put("occupied", it) }
                    },
            )
        }
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
