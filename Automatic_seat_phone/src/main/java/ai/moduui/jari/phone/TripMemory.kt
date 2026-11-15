package ai.moduui.jari.phone

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * 다녀온 여정 기억 + 사람별 자주 앉는 자리. 추천이 "막 만드는" 게 아니라 이 가족이 실제로 앉던 대로 나오게 함.
 *
 *  - RideRecord: 차에서 내리기 직전 마지막 상태 (누가 어디에 · 좌석 위치 · 목적지 · 거리 · 짐)
 *  - personHabits: 좌석 기억(SeatRecord)을 사람별로 묶어 자주 앉는 자리 순서로
 *  - similarRides: 이번 여정과 사람 · 짐 · 목적지 · 거리가 비슷한 지난 여정 (점수 0..100)
 *  - PlanMemory: 위 둘을 규칙 배치 · LLM 프롬프트 · 안 고르기에 넘기는 묶음
 */
data class RideRecord(
    val at: Long,
    val destination: String,
    /** 경로 거리(km). 차가 routeKm 를 보내 줄 때만 */
    val km: Double?,
    /** 출발부터 도착까지 걸린 분 (폰이 본 값) */
    val minutes: Int?,
    val trip: TripDraft,
    /** 짐 부피 합(L, 적어 둔 크기 기준) */
    val luggageLiters: Int,
    /** 좌석 → 앉은 사람 (운전석 포함) */
    val seats: Map<String, String>,
    /** 좌석 → (앞뒤, 등받이) 내리기 직전 위치. 모르면 null */
    val positions: Map<String, Pair<Int?, Int?>>,
) {
    val riders: List<String> get() = seats.values.filter { it != "운전자" }
}

/** 한 사람이 한 자리에 앉은 기록 묶음 */
data class SeatUse(val seat: String, val times: Int, val last: SeatRecord)

/** 사람별 기억: 자주 앉는 자리 순 (같으면 최근 것 먼저) */
data class PersonHabit(val who: String, val detail: String, val uses: List<SeatUse>) {
    val total: Int get() = uses.sumOf { it.times }
    val top: SeatUse? get() = uses.firstOrNull()

    /** 프롬프트 · 화면용: "2열 오른쪽 5번, 2열 왼쪽 1번" */
    fun text(limit: Int = 2): String = uses.take(limit).joinToString(", ") { "${seatName(it.seat)} ${it.times}번" }
}

fun personHabits(records: List<SeatRecord>): List<PersonHabit> =
    records.filter { it.who.isNotBlank() && it.who != "운전자" }
        .groupBy { it.who }
        .map { (who, list) ->
            val uses = list.groupBy { it.seat }
                .map { (seat, l) -> SeatUse(seat, l.size, l.maxBy { it.at }) }
                .sortedWith(compareByDescending<SeatUse> { it.times }.thenByDescending { it.last.at })
            PersonHabit(who, list.maxBy { it.at }.detail, uses)
        }
        .sortedByDescending { h -> h.uses.maxOf { it.last.at } }

/** 비슷한 지난 여정 하나와 점수 · 이유 ("같은 사람 3/3 · 짐 비슷 · 같은 목적지") */
data class SimilarRide(val ride: RideRecord, val score: Int, val why: List<String>)

/** 이번 여정 (비교할 것) */
data class RideQuery(val riders: List<String>, val luggageLiters: Int, val destination: String = "", val km: Double? = null)

object RideMatch {
    /** "아이2" → "아이": 이름 없이 넣은 사람은 종류로 비교 */
    private fun base(label: String) = label.trimEnd { it.isDigit() }

    /** 사람 겹침 (0..1): 같은 이름 · 같은 종류(이름 없는 아이 등)를 하나씩 짝지음 */
    fun riderOverlap(a: List<String>, b: List<String>): Pair<Double, Int> {
        if (a.isEmpty() && b.isEmpty()) return 1.0 to 0
        val left = b.map(::base).toMutableList()
        var same = 0
        for (x in a.map(::base)) if (left.remove(x)) same++
        val union = a.size + b.size - same
        return (if (union == 0) 1.0 else same.toDouble() / union) to same
    }

    fun score(q: RideQuery, r: RideRecord): SimilarRide {
        val why = mutableListOf<String>()
        var sum = 0.0
        var weight = 0.0
        // 사람 50 · 짐 20 · 목적지 20 · 거리 10. 모르는 항목은 빼고 나머지로 환산
        val (ov, same) = riderOverlap(q.riders, r.riders)
        sum += 50 * ov; weight += 50
        if (same > 0) why += "같은 사람 $same/${max(q.riders.size, r.riders.size)}"
        val big = max(max(q.luggageLiters, r.luggageLiters), 60)
        val lug = 1.0 - abs(q.luggageLiters - r.luggageLiters).toDouble() / big
        sum += 20 * lug.coerceIn(0.0, 1.0); weight += 20
        if (lug >= 0.75) why += if (q.luggageLiters == 0 && r.luggageLiters == 0) "짐 없음" else "짐 비슷"
        if (q.destination.isNotBlank() && r.destination.isNotBlank()) {
            weight += 20
            if (q.destination.trim() == r.destination.trim()) {
                sum += 20; why += "같은 목적지"
            }
        }
        val qk = q.km
        val rk = r.km
        if (qk != null && rk != null && qk > 0 && rk > 0) {
            weight += 10
            // 거리 비가 4배면 0, 같으면 1
            val d = (1 - abs(ln(qk / rk)) / ln(4.0)).coerceIn(0.0, 1.0)
            sum += 10 * d
            if (d >= 0.7) why += "거리 비슷"
        }
        return SimilarRide(r, (sum / weight * 100).toInt(), why)
    }

    /** 점수 높은 순. minScore 보다 낮으면 뺌 */
    fun similar(q: RideQuery, rides: List<RideRecord>, minScore: Int = 55, limit: Int = 3): List<SimilarRide> =
        rides.map { score(q, it) }.filter { it.score >= minScore }
            .sortedWith(compareByDescending<SimilarRide> { it.score }.thenByDescending { it.ride.at })
            .take(limit)
}

/**
 * 추천에 쓰는 기억 묶음. prefer: 사람 → 자주 앉던 자리 순서. similar: 비슷한 지난 여정.
 */
data class PlanMemory(val habits: List<PersonHabit> = emptyList(), val similar: List<SimilarRide> = emptyList()) {
    val isEmpty: Boolean get() = habits.isEmpty() && similar.isEmpty()

    fun habitOf(label: String): PersonHabit? = habits.firstOrNull { it.who == label }

    /**
     * 사람마다 앉을 자리 후보 순서. 아주 비슷한 지난 여정(80점 이상)의 자리를 먼저, 그다음 자주 앉던 자리.
     */
    fun prefer(riders: List<Rider>): Map<String, List<String>> {
        val best = similar.firstOrNull()?.takeIf { it.score >= 80 }?.ride
        return riders.filter { !it.driver }.associate { r ->
            val fromRide = best?.seats?.entries?.firstOrNull { it.value == r.label }?.key
            r.label to (listOfNotNull(fromRide) + habitOf(r.label)?.uses?.map { it.seat }.orEmpty()).distinct()
        }.filterValues { it.isNotEmpty() }
    }

    /** 이 안이 평소 자리와 얼마나 맞는지: (맞은 사람, 기억 있는 사람) */
    fun match(plan: Map<String, String>, riders: List<Rider>): Pair<Int, Int> {
        val pref = prefer(riders)
        var hit = 0
        for ((label, seats) in pref) {
            val at = plan.entries.firstOrNull { it.value == label }?.key
            if (at != null && at == seats.first()) hit++
        }
        return hit to pref.size
    }

    /** 화면에 보여 줄 "참고한 기억" 줄 */
    fun summary(riders: List<Rider>): List<String> = buildList {
        for (s in similar.take(2)) {
            val day = java.text.SimpleDateFormat("M월 d일", java.util.Locale.KOREAN).format(java.util.Date(s.ride.at))
            val dest = s.ride.destination.ifBlank { "목적지 모름" }
            add("비슷한 여정 ${s.score}% · $day $dest" + if (s.why.isEmpty()) "" else " (${s.why.joinToString(" · ")})")
        }
        for (r in riders) {
            val h = habitOf(r.label) ?: continue
            add("${r.label} 평소 ${h.text()}")
        }
    }
}

object RideStore {
    fun encode(list: List<RideRecord>): String = JSONArray().apply {
        for (r in list) {
            val t = r.trip
            put(
                JSONObject()
                    .put("at", r.at).put("destination", r.destination).put("luggageLiters", r.luggageLiters)
                    .put("seniors", t.seniors).put("adults", t.adults).put("children", t.children).put("infants", t.infants)
                    .put("strollers", t.strollers).put("suitcases", t.suitcases).put("babyBags", t.babyBags)
                    .put("seats", JSONObject().apply { r.seats.forEach { (k, v) -> put(k, v) } })
                    .put(
                        "positions",
                        JSONObject().apply {
                            r.positions.forEach { (k, v) ->
                                put(k, JSONObject().apply { v.first?.let { put("f", it) }; v.second?.let { put("b", it) } })
                            }
                        },
                    )
                    .apply {
                        r.km?.let { put("km", it) }
                        r.minutes?.let { put("minutes", it) }
                    },
            )
        }
    }.toString()

    fun decode(s: String?): List<RideRecord> = runCatching {
        val a = JSONArray(s ?: "[]")
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val seats = o.optJSONObject("seats")
            val pos = o.optJSONObject("positions")
            RideRecord(
                at = o.getLong("at"),
                destination = o.optString("destination"),
                km = if (o.has("km")) o.optDouble("km") else null,
                minutes = if (o.has("minutes")) o.optInt("minutes") else null,
                trip = TripDraft(
                    seniors = o.optInt("seniors"), adults = o.optInt("adults"), children = o.optInt("children"), infants = o.optInt("infants"),
                    strollers = o.optInt("strollers"), suitcases = o.optInt("suitcases"), babyBags = o.optInt("babyBags"),
                ),
                luggageLiters = o.optInt("luggageLiters"),
                seats = seats?.keys()?.asSequence()?.associateWith { seats.getString(it) }.orEmpty(),
                positions = pos?.keys()?.asSequence()?.associateWith { k ->
                    val p = pos.getJSONObject(k)
                    (if (p.has("f")) p.getInt("f") else null) to (if (p.has("b")) p.getInt("b") else null)
                }.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())
}
