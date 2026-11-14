package ai.moduui.jari.phone

import org.json.JSONObject

data class RunStep(val label: String, val outcome: String, val terminal: Boolean, val ok: Boolean)

data class RunInfo(
    val reason: String,
    val done: Int,
    val total: Int,
    val countdown: Int,
    val finished: Boolean,
    /** 모든 단계가 제대로 끝남. 문이 열려 멈췄거나 건너뛴 단계가 있으면 false */
    val allOk: Boolean,
    val steps: List<RunStep>,
)

/** 차가 알려 준 좌석 하나 (위치 0..100: 앞뒤는 100 = 맨 앞, 등받이는 100 = 가장 세움). 모르면 null */
data class SeatState(
    val id: String,
    val row: Int,
    val right: Boolean,
    val label: String,
    val foreAft: Int?,
    val backrest: Int?,
    val canForeAft: Boolean,
    val canBackrest: Boolean,
    val occupied: Boolean?,
)

/**
 * 차가 어림한 트렁크 (SDK 에 트렁크 API 가 없어 좌석 줄 수 · 마지막 줄 좌석 위치 · 3열 접힘 · 짐 부피로 셈).
 * liters 쓸 수 있는 용량, used 짐에 필요한 부피, folded 짐 때문에 접는 좌석 id.
 */
data class TrunkState(val liters: Int, val used: Int, val rows: Int, val slide: Int, val folded: Set<String>, val basis: String) {
    companion object {
        /** 차가 트렁크 값을 안 보낼 때 (예전 차 앱): 여정 짐만으로 휴대폰에서 어림 */
        fun guess(seats: List<SeatState>, trip: TripDraft?): TrunkState {
            val rows = seats.maxOfOrNull { it.row } ?: 2
            val raw = trip?.let { it.strollers * 130 + it.suitcases * 90 + it.babyBags * 30 } ?: 0
            return TrunkState(if (rows >= 3) 500 else 600, (raw / 0.75).toInt(), rows, 0, emptySet(), "휴대폰 어림 · 차 값 없음")
        }
    }
}

data class RestCandidate(val name: String, val km: Double, val eta: Int, val nursingRoom: Boolean)

data class RestInfo(val reason: String, val nursing: Boolean, val added: String, val candidates: List<RestCandidate>)

/** 차 앱이 중계 서버에 올린 상태 (차의 PhoneLink.statusJson 과 같은 모양) */
data class CarStatus(
    val gear: String,
    val speed: Int,
    val driving: Boolean,
    val departed: Boolean,
    val arrived: Boolean,
    val trip: TripDraft?,
    val tripSource: String,
    val planSummary: String?,
    val planNotes: List<String>,
    val planSdk: Int,
    val planGuide: Int,
    val run: RunInfo?,
    val pending: Int,
    val babyState: String,
    val babyLabel: String,
    val babyMinutes: Long,
    val babySource: String,
    val minutesToFeed: Int?,
    val lastFeed: String?,
    val rest: RestInfo?,
    val comfort: List<String>,
    val quiet: Boolean,
    val voice: String?,
    val remainMin: Long?,
    val babyEtaMin: Int?,
    val rearAlert: Boolean,
    val seats: List<SeatState> = emptyList(),
    val trunk: TrunkState? = null,
    /** 선택 키 (차가 보내면): 내비 경로 총 거리(km) · 내비 목적지 이름 */
    val routeKm: Double? = null,
    val naviDestination: String? = null,
) {
    /** 여정 기억에 쓸 목적지: 내비 목적지 → 여정의 목적지 */
    val destinationName: String get() = naviDestination?.takeIf { it.isNotBlank() } ?: trip?.destination.orEmpty()

    companion object {
        fun parse(j: JSONObject): CarStatus {
            val t = j.optJSONObject("trip")
            val p = j.optJSONObject("plan")
            val r = j.optJSONObject("run")
            val b = j.optJSONObject("baby") ?: JSONObject()
            val rs = j.optJSONObject("rest")
            return CarStatus(
                gear = j.optString("gear"),
                speed = j.optInt("speed"),
                driving = j.optBoolean("driving"),
                departed = j.optBoolean("departed"),
                arrived = j.optBoolean("arrived"),
                trip = t?.let {
                    TripDraft(
                        destination = it.optString("destination"),
                        seniors = it.optInt("seniors"),
                        adults = it.optInt("adults"),
                        children = it.optInt("children"),
                        infants = it.optInt("infants"),
                        strollers = it.optInt("strollers"),
                        suitcases = it.optInt("suitcases"),
                        babyBags = it.optInt("babyBags"),
                    )
                },
                tripSource = t?.optString("source").orEmpty(),
                planSummary = p?.optString("summary"),
                planNotes = p?.optJSONArray("notes")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
                planSdk = p?.optInt("sdk") ?: 0,
                planGuide = p?.optInt("guide") ?: 0,
                run = r?.let {
                    val a = it.optJSONArray("steps")
                    RunInfo(
                        reason = it.optString("reason"),
                        done = it.optInt("done"),
                        total = it.optInt("total"),
                        countdown = it.optInt("countdown", -1),
                        finished = it.optBoolean("finished"),
                        allOk = it.optBoolean("allOk"),
                        steps = if (a == null) emptyList() else List(a.length()) { i ->
                            val s = a.getJSONObject(i)
                            RunStep(s.optString("label"), s.optString("outcome"), s.optBoolean("terminal"), s.optBoolean("ok"))
                        },
                    )
                },
                pending = j.optInt("pending"),
                babyState = b.optString("state", "UNKNOWN"),
                babyLabel = b.optString("label"),
                babyMinutes = b.optLong("minutes", -1),
                babySource = b.optString("source"),
                minutesToFeed = if (j.has("minutesToFeed")) j.optInt("minutesToFeed") else null,
                lastFeed = j.optString("lastFeed").ifBlank { null },
                rest = rs?.let {
                    val a = it.optJSONArray("candidates")
                    RestInfo(
                        reason = it.optString("reason"),
                        nursing = it.optBoolean("nursing"),
                        added = it.optString("added"),
                        candidates = if (a == null) emptyList() else List(a.length()) { i ->
                            val c = a.getJSONObject(i)
                            RestCandidate(c.optString("name"), c.optDouble("km"), c.optInt("eta"), c.optBoolean("nursingRoom"))
                        },
                    )
                },
                comfort = j.optJSONArray("comfort")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
                quiet = j.optBoolean("quiet"),
                voice = j.optString("voice").ifBlank { null },
                remainMin = if (j.has("remainMin")) j.optLong("remainMin") else null,
                babyEtaMin = if (j.has("babyEtaMin")) j.optInt("babyEtaMin") else null,
                rearAlert = j.optBoolean("rearAlert"),
                seats = j.optJSONArray("seats")?.let { a ->
                    List(a.length()) { i ->
                        val o = a.getJSONObject(i)
                        SeatState(
                            id = o.optString("id"),
                            row = o.optInt("row", 1),
                            right = o.optString("side") == "RIGHT",
                            label = o.optString("label"),
                            foreAft = if (o.has("foreAft")) o.optInt("foreAft") else null,
                            backrest = if (o.has("backrest")) o.optInt("backrest") else null,
                            canForeAft = o.optBoolean("foreAftMove"),
                            canBackrest = o.optBoolean("backrestMove"),
                            occupied = if (o.has("occupied")) o.optBoolean("occupied") else null,
                        )
                    }
                }.orEmpty().let { list ->
                    // 모든 좌석이 앞뒤 0 · 등받이 0 이면 실제로 읽은 값이 아니라 기본값으로 봄 (에뮬레이터 SDK 읽기) → 모름
                    if (list.isNotEmpty() && list.all { it.foreAft == 0 && it.backrest == 0 }) list.map { it.copy(foreAft = null, backrest = null) } else list
                },
                routeKm = if (j.has("routeKm")) j.optDouble("routeKm").takeIf { !it.isNaN() && it > 0 } else null,
                naviDestination = j.optString("naviDestination").ifBlank { null },
                trunk = j.optJSONObject("trunk")?.let { o ->
                    TrunkState(
                        liters = o.optInt("liters"),
                        used = o.optInt("used"),
                        rows = o.optInt("rows", 2),
                        slide = o.optInt("slide"),
                        folded = o.optJSONArray("folded")?.let { a -> List(a.length()) { a.optString(it) }.toSet() }.orEmpty(),
                        basis = o.optString("basis"),
                    ).let { t ->
                        // 좌석 위치가 기본값(0)뿐이면 차가 그 0으로 "뒤로 가 있음"을 셈한 것이라 보정을 되돌림
                        val allZero = j.optJSONArray("seats")?.let { a ->
                            a.length() > 0 && (0 until a.length()).all { a.optJSONObject(it)?.let { s -> s.optInt("foreAft", -1) == 0 && s.optInt("backrest", -1) == 0 } == true }
                        } == true
                        if (allZero && t.slide != 0) t.copy(liters = t.liters - t.slide, slide = 0, basis = (if (t.rows >= 3) "3열 차" else "2열 차") + " 기본값 · 좌석 위치 모름") else t
                    }
                },
            )
        }
    }
}
