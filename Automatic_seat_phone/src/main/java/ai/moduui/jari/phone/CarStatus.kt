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
) {
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
            )
        }
    }
}
