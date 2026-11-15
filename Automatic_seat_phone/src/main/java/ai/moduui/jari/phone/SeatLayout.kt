package ai.moduui.jari.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * 2D 자리 배치 (홈 > 여정 입력). 자리는 보호자가 직접 고르고, AI 는 자리마다 자세(등받이 · 앞뒤)만 정함.
 *
 *  - 자리를 누르면 구성원(이봄) 또는 종류(어른 · 어르신 · 아이 · 아기)를 넣음
 *  - 트렁크를 누르면 구성원 짐을 넣거나 글로 적어 넣음 ("24인치 캐리어")
 *  - 여정(TripDraft)은 이 배치에서 셈 (차로 보내는 인원 · 짐 수)
 */

/** 자리에 넣은 사람. memberId 가 있으면 구성원, 없으면 종류만 ("아이") */
data class Placed(val label: String, val who: Who, val need: SeatNeed?, val memberId: Long? = null, val note: String = "") {
    val needsChildSeat: Boolean get() = need == SeatNeed.REAR_SEAT || need == SeatNeed.CAR_SEAT || need == SeatNeed.BOOSTER

    /** 차 여정 칸 (Member.kind 와 같은 기준) */
    val slot: Slot
        get() = when (need) {
            SeatNeed.REAR_SEAT, SeatNeed.CAR_SEAT -> Slot.INFANT
            SeatNeed.BOOSTER -> Slot.CHILD
            SeatNeed.BELT -> Slot.ADULT
            null -> if (who == Who.SENIOR) Slot.SENIOR else Slot.ADULT
        }

    /** "아기 · 뒤보기 카시트" */
    val detail: String get() = listOfNotNull(who.label, need?.takeIf { it != SeatNeed.BELT }?.label).joinToString(" · ")

    companion object {
        fun of(m: Member) = Placed(m.name, m.who, m.need, m.id, m.note)
    }
}

/** 종류로 넣기 (이름 없이) */
enum class GenericRider(val label: String, val who: Who, val need: SeatNeed?) {
    ADULT("어른", Who.ADULT, null),
    SENIOR("어르신", Who.SENIOR, null),
    CHILD("아이", Who.CHILD, SeatNeed.BOOSTER),
    BABY("아기", Who.BABY, SeatNeed.CAR_SEAT),
    ;

    val chip: String get() = if (need == null) label else "$label · ${need.label}"
}

/**
 * 트렁크에 넣은 짐. luggageId 가 있으면 구성원 탭의 짐, 없으면 이번에 글로 적은 짐.
 * weight 는 화면 표시용 무게 ("무거움" · "18kg", 모르면 빈 값)
 */
data class TrunkPut(val id: Long, val label: String, val bag: Bag, val liters: Int, val luggageId: Long? = null, val weight: String = "") {
    /** 글로 넣기 결과: 읽음 (크기를 적었는지) · 형식이 틀림 (이유) */
    sealed interface Form {
        data class Ok(val put: TrunkPut, val sizeKnown: Boolean) : Form
        data class Bad(val why: String) : Form
    }

    /** 칩 글자: "유모차 · 190L · 무거움" */
    val chip: String get() = listOf(label, "${liters}L", weight).filter { it.isNotEmpty() }.joinToString(" · ")

    companion object {
        fun of(l: LuggageItem) = TrunkPut(l.id, l.name, l.bag, l.estLiters, l.id, l.weightKg?.let { "${it}kg" }.orEmpty())

        /** 글에 크기(리터 · 인치 · 가로x세로x높이)를 적었는지 — 적었으면 크기 칩보다 글이 우선 */
        fun wroteSize(text: String): Boolean =
            PresetParser.luggageFrom(text, "", "", "", 0)?.let { it.liters != null || it.inches != null } == true

        /**
         * 글로 적은 짐: "24인치 캐리어" · "디럭스 유모차" · "아이스박스 60x40x40".
         * 부피: 글에 적은 크기 > 크기 칩 > (캐리어류) 무게 어림 > 종류 기본값
         */
        /**
         * 글로 넣기 형식 (11/15 사용자 결정): "이름 / 크기 / 무게".
         *  - "추석 선물 / 40x30x20 / 5kg" · "캐리어 / 24인치 / 18kg" · "아이스박스 / 45L /"
         *  - 크기를 모르면 비움: "추석 선물 // 5kg" (부피는 종류 기본값)
         *  - 크기: 가로x세로x높이(cm) · 리터(L) · 인치. 무게: kg (숫자만 적어도 kg)
         * 형식이 맞지 않으면 [Form.Bad] 에 이유.
         */
        fun form(text: String, id: Long = System.currentTimeMillis()): Form {
            val t = text.trim()
            if (t.isEmpty()) return Form.Bad("")
            if ('/' !in t) return Form.Bad("이름 / 크기 / 무게 로 적어 주세요 (크기를 모르면 이름 // 무게)")
            val parts = t.split('/').map { it.trim() }
            if (parts.size > 3) return Form.Bad("/ 는 두 번까지만 써요: 이름 / 크기 / 무게")
            val name = parts[0]
            val size = parts.getOrElse(1) { "" }
            val weight = parts.getOrElse(2) { "" }
            if (name.isEmpty()) return Form.Bad("맨 앞에 짐 이름을 적어 주세요")
            if (size.isEmpty() && weight.isEmpty()) return Form.Bad("크기나 무게 중 하나는 적어 주세요")
            val l = PresetParser.luggageFrom(name, size, "", "", id) ?: return Form.Bad("짐 이름을 읽지 못했어요")
            if (size.isNotEmpty() && l.liters == null && l.inches == null) {
                return Form.Bad("크기 \"$size\" 를 읽지 못했어요 · 40x30x20 · 45L · 24인치 처럼")
            }
            val kg = if (weight.isEmpty()) null else Regex("""^(\d{1,3})\s*(kg|킬로|키로)?$""", RegexOption.IGNORE_CASE).find(weight)?.groupValues?.get(1)?.toInt()
            if (weight.isNotEmpty() && (kg == null || kg !in 1..60)) {
                return Form.Bad("무게 \"$weight\" 를 읽지 못했어요 · 1~60kg 사이 숫자로 (예: 5kg)")
            }
            val item = l.copy(name = name, weightKg = kg)
            return Form.Ok(TrunkPut(id, name, item.bag, item.estLiters, weight = kg?.let { "${it}kg" }.orEmpty()), sizeKnown = size.isNotEmpty())
        }

        fun typed(text: String, id: Long = System.currentTimeMillis(), size: RoughSize? = null, weight: RoughWeight? = null): TrunkPut? {
            val l = PresetParser.luggageFrom(text, "", "", "", id) ?: return null
            val withWeight = if (weight != null && l.weightKg == null) l.copy(weightKg = weight.kg) else l
            val liters = when {
                l.liters != null || l.inches != null -> l.estLiters
                size != null -> size.liters(l.bag)
                else -> withWeight.estLiters
            }
            val w = l.weightKg?.let { "${it}kg" } ?: weight?.label.orEmpty()
            return TrunkPut(id, l.name, l.bag, liters, weight = w)
        }
    }
}

data class SeatLayout(val seats: Map<String, Placed> = emptyMap(), val trunk: List<TrunkPut> = emptyList()) {
    val isEmpty: Boolean get() = seats.isEmpty() && trunk.isEmpty()

    /** 운전석에 넣은 사람 (없으면 "운전자") */
    val driverLabel: String get() = seats[DRIVER]?.label ?: "운전자"

    /** 자리 → 이름 (운전석 포함). 기존 좌석 기억 · 여정 기억과 같은 모양 */
    val plan: Map<String, String> get() = linkedMapOf(DRIVER to driverLabel) + seats.filterKeys { it != DRIVER }.mapValues { it.value.label }

    /** 탈 사람 (운전자 포함) */
    val riders: List<Rider>
        get() = listOf(Rider(driverLabel, Who.ADULT, null, seats[DRIVER]?.note.orEmpty(), driver = true)) +
            seats.filterKeys { it != DRIVER }.values.map { Rider(it.label, it.who, it.need, it.note) }

    /** 차로 보내는 여정 (adults 는 운전자 뺀 수) */
    val draft: TripDraft
        get() {
            var d = TripDraft()
            for (p in seats.filterKeys { it != DRIVER }.values) d = TripParser.with(d, p.slot, TripParser.count(d, p.slot) + 1)
            for (t in trunk) d = TripParser.with(d, t.bag.slot, TripParser.count(d, t.bag.slot) + 1)
            return d
        }

    /** 넣은 구성원 · 짐 id (운전석 사람은 여정 칸에 안 셈) */
    val picked: List<Long>
        get() = seats.filterKeys { it != DRIVER }.values.mapNotNull { it.memberId } + trunk.mapNotNull { it.luggageId }

    /** 짐 부피 합 (L, 빈틈 계산 전) */
    val luggageLiters: Int get() = trunk.sumOf { it.liters }

    /** 아이들의 카시트 단계 (차 trip.seatNeeds) */
    val seatNeeds: List<SeatNeed> get() = seats.filterKeys { it != DRIVER }.values.mapNotNull { it.need }

    /** 이 사람을 이 자리에 넣음 (같은 구성원이 다른 자리에 있으면 옮김) */
    fun put(seat: String, p: Placed): SeatLayout {
        val rest = if (p.memberId != null) seats.filterValues { it.memberId != p.memberId } else seats
        return copy(seats = rest + (seat to unique(p, rest.filterKeys { it != seat })))
    }

    fun clear(seat: String) = copy(seats = seats - seat)

    fun addTrunk(t: TrunkPut) = if (t.luggageId != null && trunk.any { it.luggageId == t.luggageId }) this else copy(trunk = trunk + t)

    fun removeTrunk(id: Long) = copy(trunk = trunk.filter { it.id != id })

    /** 종류로 넣은 사람이 겹치면 "아이2" 처럼 */
    private fun unique(p: Placed, others: Map<String, Placed>): Placed {
        if (p.memberId != null) return p
        val base = p.label.trimEnd { it.isDigit() }
        val used = others.values.map { it.label }.toSet()
        if (base !in used) return p.copy(label = base)
        var i = 2
        while ("$base$i" in used) i++
        return p.copy(label = "$base$i")
    }

    companion object {
        const val DRIVER = "R1_LEFT"
    }
}

/** 자리 규칙 (안전). 넣기 전에 막음 */
object LayoutRules {
    /** 이 자리에 넣을 수 없으면 이유 */
    fun blocked(seat: String, p: Placed): String? = when {
        seat == SeatLayout.DRIVER && (p.who == Who.CHILD || p.who == Who.BABY || p.needsChildSeat) -> "운전석에는 어른만 앉을 수 있어요"
        p.needsChildSeat && seatRow(seat) == 1 -> "${p.need?.label} 아이는 앞좌석에 앉을 수 없어요 (에어백)"
        else -> null
    }

    /** 확인할 점 (막지는 않음) */
    fun warnings(l: SeatLayout, trunkLiters: Int): List<String> = buildList {
        for ((seat, p) in l.seats) {
            if (p.need == SeatNeed.REAR_SEAT || p.need == SeatNeed.CAR_SEAT) {
                val near = l.seats.any { (s, o) -> s != seat && s != SeatLayout.DRIVER && seatRow(s) == seatRow(seat) && o.need == null && (o.who == Who.ADULT || o.who == Who.SENIOR) }
                if (!near) add("${p.label} 옆에 보호자 어른이 없어요")
            }
            if (p.who == Who.SENIOR && seatRow(seat) >= 3) add("${p.label.josa("은", "는")} 3열이라 타고 내리기 힘들어요")
        }
        val need = (l.luggageLiters / 0.75).toInt()
        if (trunkLiters > 0 && need > trunkLiters) add("짐이 트렁크 약 ${trunkLiters}L보다 많아요 (빈틈 포함 약 ${need}L)")
    }
}

object LayoutStore {
    fun encode(l: SeatLayout): String = JSONObject()
        .put(
            "seats",
            JSONObject().apply {
                for ((seat, p) in l.seats) {
                    put(
                        seat,
                        JSONObject().put("label", p.label).put("who", p.who.name).put("note", p.note).apply {
                            p.need?.let { put("need", it.name) }
                            p.memberId?.let { put("memberId", it) }
                        },
                    )
                }
            },
        )
        .put(
            "trunk",
            JSONArray(
                l.trunk.map { t ->
                    JSONObject().put("id", t.id).put("label", t.label).put("bag", t.bag.name).put("liters", t.liters).apply {
                        t.luggageId?.let { put("luggageId", it) }
                        if (t.weight.isNotEmpty()) put("weight", t.weight)
                    }
                },
            ),
        )
        .toString()

    fun decode(s: String?): SeatLayout {
        if (s.isNullOrBlank()) return SeatLayout()
        return runCatching {
            val o = JSONObject(s)
            val so = o.optJSONObject("seats") ?: JSONObject()
            val seats = so.keys().asSequence().associateWith { k ->
                val p = so.getJSONObject(k)
                Placed(
                    p.getString("label"),
                    runCatching { Who.valueOf(p.optString("who")) }.getOrDefault(Who.ADULT),
                    runCatching { SeatNeed.valueOf(p.getString("need")) }.getOrNull(),
                    if (p.has("memberId")) p.getLong("memberId") else null,
                    p.optString("note"),
                )
            }
            val ta = o.optJSONArray("trunk") ?: JSONArray()
            val trunk = List(ta.length()) { i ->
                val t = ta.getJSONObject(i)
                TrunkPut(
                    t.getLong("id"), t.getString("label"),
                    runCatching { Bag.valueOf(t.optString("bag")) }.getOrDefault(Bag.OTHER),
                    t.optInt("liters", Bag.OTHER.defaultLiters),
                    if (t.has("luggageId")) t.getLong("luggageId") else null,
                    t.optString("weight"),
                )
            }
            SeatLayout(seats, trunk)
        }.getOrDefault(SeatLayout())
    }
}

// ───────────────────────────── AI 자세 (등받이 · 앞뒤) ─────────────────────────────

/**
 * 폰 안 LLM 이 자리마다 자세를 냄 → 규칙이 검사 · 고침 (PostureRules).
 * 카시트 자리 등받이 · 뒤보기 카시트 앞자리 공간은 안전 규칙이라 LLM 답과 상관없이 규칙대로.
 */
object PosturePrompt {
    val SYSTEM: String = buildString {
        appendLine("너는 차 좌석 자세 도우미다. 자리마다 앉은 사람에게 맞는 등받이 각도와 앞뒤 위치를 정한다.")
        appendLine("등받이 각도는 세로에서 뒤로 기운 각도다. 15도가 가장 세움. 보통 24도.")
        appendLine("앞뒤는 0~100, 100이 맨 앞, 0이 맨 뒤. 보통 50.")
        appendLine("주행 중 한계 (넘지 않는다):")
        appendLine("- 운전석(R1_LEFT)은 답하지 않는다. 운전자가 운전할 수 있게 직접 맞춘다.")
        appendLine("- 조수석(R1_RIGHT)은 15~30도, 앞뒤 75 이하 (에어백과 거리).")
        appendLine("- 뒷좌석(R2_, R3_)은 15~35도.")
        appendLine("규칙:")
        appendLine("1. 카시트·부스터 아이 자리는 20도로 세운다 (카시트 고정).")
        appendLine("2. 어르신은 22도, 멀미하는 사람은 20도, 자려는 사람은 32도.")
        appendLine("3. [비슷한 여정: ...] 이 있으면 그 자세를 먼저 따르고, 없으면 [평소: ...] 를 따른다.")
        appendLine("4. 뒤에 다리 긴 어른이 앉으면 앞자리는 앞뒤 60 이상으로 당긴다.")
        appendLine("5. (기타: ...) 에 적힌 몸 상태를 꼭 따른다. 허리가 아프거나 누워야 하거나 잘 사람은 32도, 멀미는 20도.")
        appendLine("6. [짐: 마지막 줄 앞뒤 N 이상] 이 있으면 그 줄 자리의 앞뒤를 N 이상으로 한다 (짐이 트렁크에 들어가게).")
        appendLine("답은 자리마다 한 줄씩 '자리=각도도 앞뒤숫자' 로만 쓴다. 설명은 쓰지 않는다.")
        appendLine("보기:")
        appendLine("R1_RIGHT: A (어르신)")
        appendLine("R2_LEFT: C (어른, 기타: 허리가 아파 누워야 함)")
        appendLine("R2_RIGHT: B (아기, 카시트)")
        appendLine("[짐: 마지막 줄 앞뒤 70 이상]")
        appendLine("답:")
        appendLine("R1_RIGHT=22도 앞뒤40")
        appendLine("R2_LEFT=32도 앞뒤70")
        append("R2_RIGHT=20도 앞뒤70")
    }

    /**
     * 자리마다 한 줄. 검색해 온 기억을 같이 줌 (RAG):
     *  - [비슷한 여정 87%: ...] 사람 · 짐 · 목적지 · 거리가 비슷한 지난 여정에서 이 사람이 내리기 전 자세
     *  - [평소: ...] 이 사람의 마지막 자세
     */
    fun build(plan: Map<String, String>, riders: List<Rider>, memory: List<SeatRecord>, similar: SimilarRide? = null, lastRowFore: Int? = null): String = buildString {
        val byName = riders.associateBy { it.label }
        for ((seat, name) in plan.entries.sortedBy { it.key }) {
            val r = byName[name] ?: continue
            if (r.driver) continue
            append(seat).append(": ").append(r.describe)
            similar?.let { s -> PastPose.of(s.ride, name, seat)?.let { (f, b) -> append(" [비슷한 여정 ${s.score}%: ${backrestDeg(b)}도").append(f?.let { " 앞뒤$it" } ?: "").append("]") } }
            memory.filter { it.who == name && it.backrest != null }.maxByOrNull { it.at }?.let { m ->
                append(" [평소: ${backrestDeg(m.backrest!!)}도").append(m.foreAft?.let { " 앞뒤$it" } ?: "").append("]")
            }
            appendLine()
        }
        lastRowFore?.let { appendLine("[짐: 마지막 줄 앞뒤 $it 이상]") }
        append("답:")
    }

    private val LINE = Regex("""(R[1-3]_(?:LEFT|RIGHT))\s*[=:]\s*(\d{1,2})\s*(?:도|°)?(?:[^0-9\n]*?앞뒤\s*(\d{1,3}))?""")

    /** LLM 답 → 자리별 (각도, 앞뒤). 앞뒤를 안 쓰면 null */
    fun parse(text: String): Map<String, Pair<Int, Int?>> = buildMap {
        for (m in LINE.findAll(text)) {
            val seat = m.groupValues[1]
            if (seat in this) continue
            val deg = m.groupValues[2].toIntOrNull() ?: continue
            put(seat, deg to m.groupValues[3].toIntOrNull())
        }
    }
}

/** AI 자세 검사 결과: 자리별 자세 + 규칙이 고친 것 */
data class PostureResult(val guess: Map<String, SeatGuess>, val fixes: List<String>, val aiSeats: Int)

object PostureRules {
    /** 뒷좌석: 주행 중 가장 눕힌 각도 (안전띠가 몸에 맞게) */
    const val MAX_RIDE_DEG = 35

    /** 조수석: 주행 중 가장 눕힌 각도 · 가장 앞 (에어백과 거리) */
    const val MAX_FRONT_DEG = 30
    const val FRONT_MAX_FORE = 75

    /** 운전석: 운전할 수 있는 등받이 · 앞뒤 범위와 보통값 (페달 · 핸들에 닿고 시야가 나오는 자세) */
    const val DRIVE_MIN_DEG = 18
    const val DRIVE_MAX_DEG = 28
    const val DRIVE_MIN_FORE = 25
    const val DRIVE_MAX_FORE = 75
    const val DRIVE_DEG = 24
    const val DRIVE_FORE = 50

    fun maxDeg(seat: String): Int = if (seatRow(seat) == 1) MAX_FRONT_DEG else MAX_RIDE_DEG

    /** 운전석 자세가 운전할 수 있는 범위인지. 모르는 값은 괜찮은 것으로 봄 */
    fun drivable(foreAft: Int?, backrest: Int?): Boolean =
        (backrest == null || backrestDeg(backrest) in DRIVE_MIN_DEG..DRIVE_MAX_DEG) && (foreAft == null || foreAft in DRIVE_MIN_FORE..DRIVE_MAX_FORE)

    /**
     * 운전석 미리보기. 차는 운전석을 자동으로 움직이지 않음(앉아 있어도 건너뛸 수 없음) → 지금 자세가 운전 범위 밖이면
     * 범위 안 가장 가까운 값을 보여 주고 직접 맞추게 함. now: 차가 알려 준 지금 (앞뒤, 등받이)
     */
    fun driverPose(now: Pair<Int?, Int?>?): SeatGuess {
        val f = now?.first
        val b = now?.second
        val deg = b?.let(::backrestDeg)
        val okDeg = deg?.coerceIn(DRIVE_MIN_DEG, DRIVE_MAX_DEG) ?: DRIVE_DEG
        val okFore = f?.coerceIn(DRIVE_MIN_FORE, DRIVE_MAX_FORE) ?: DRIVE_FORE
        val why = when {
            !drivable(f, b) -> "운전할 수 없는 자세예요 · ${DRIVE_MIN_DEG}~${DRIVE_MAX_DEG}°로 직접 맞춰 주세요"
            b == null && f == null -> "운전 자세 보통값 · 직접 맞춰요"
            else -> "지금 자세 그대로 (운전할 수 있어요)"
        }
        return SeatGuess(okFore, degToBackrest(okDeg), why)
    }

    /** 자리 한계 적용: 카시트 20° · 조수석 15~30° · 앞뒤 75 이하 · 뒷좌석 15~35°. 고친 것은 fixes 에 */
    private fun limit(seat: String, name: String, r: Rider, g: SeatGuess, fixes: MutableList<String>): SeatGuess {
        var deg = backrestDeg(g.backrest)
        var fore = g.foreAft
        var why = g.why
        if (r.needsChildSeat) {
            if (deg != SeatGuessRules.CHILD_SEAT_DEG) {
                fixes += "${name.josa("은", "는")} 카시트라 ${SeatGuessRules.CHILD_SEAT_DEG}°로 세움 (${g.why.substringBefore(" ·")} ${deg}°)"
                deg = SeatGuessRules.CHILD_SEAT_DEG
                why = "$why · 카시트는 세워서"
            }
        } else {
            val max = maxDeg(seat)
            if (deg < BACKREST_MIN_DEG || deg > max) {
                val c = deg.coerceIn(BACKREST_MIN_DEG, max)
                fixes += "$name 등받이 ${deg}° → ${c}° (${seatName(seat)} 주행 중 ${BACKREST_MIN_DEG}~${max}°)"
                deg = c
            }
        }
        if (seat == "R1_RIGHT" && fore != null && fore > FRONT_MAX_FORE) {
            fixes += "$name 앞뒤 $fore → $FRONT_MAX_FORE (에어백과 거리)"
            fore = FRONT_MAX_FORE
        }
        return SeatGuess(fore, degToBackrest(deg), why)
    }

    /**
     * ai: LLM 이 낸 자리별 (각도, 앞뒤). 비어 있으면 비슷한 여정 · 기억 · 규칙(SeatGuessRules).
     * 어느 출처든 자리 한계(limit)를 거침. 뒤보기 카시트 앞자리는 앞으로 당김. 운전석은 driverPose (미리보기만).
     */
    fun check(
        ai: Map<String, Pair<Int, Int?>>,
        plan: Map<String, String>,
        riders: List<Rider>,
        memory: List<SeatRecord>,
        similar: SimilarRide? = null,
        driverNow: Pair<Int?, Int?>? = null,
        lastRowFore: Int? = null,
        lastRow: Int = 2,
    ): PostureResult {
        val base = SeatGuessRules.guess(plan, riders, memory)
        val byName = riders.associateBy { it.label }
        val out = linkedMapOf<String, SeatGuess>()
        val fixes = mutableListOf<String>()
        var used = 0
        for ((seat, name) in plan) {
            val r = byName[name] ?: continue
            if (r.driver) {
                val d = driverPose(driverNow)
                out[seat] = d
                if (!drivable(driverNow?.first, driverNow?.second)) {
                    val nowDeg = driverNow?.second?.let(::backrestDeg)
                    fixes += "운전석이 운전할 수 없는 자세예요" + (nowDeg?.let { " (등받이 ${it}°)" } ?: "") + " · 직접 맞춰 주세요"
                }
                continue
            }
            val b = base[seat] ?: continue
            // 자세 출처: AI 답 > 비슷한 지난 여정의 이 사람 자세 > 기억 · 규칙(SeatGuessRules)
            val a = ai[seat]
            val past = similar?.let { PastPose.of(it.ride, name, seat) }
            val g = when {
                a != null -> SeatGuess(a.second?.coerceIn(0, 100) ?: b.foreAft, degToBackrest(a.first.coerceIn(0, 90)), "AI")
                past != null -> SeatGuess(past.first ?: b.foreAft, past.second, "비슷한 여정 ${similar?.score ?: 0}%")
                else -> b
            }
            if (a != null) used++
            // 구성원 특징(허리 · 누워야 · 멀미 등)은 AI 가 놓쳐도 지킴 (0.6B 모델이 기타를 무시하는 일이 있음, 11/15)
            val g2 = SeatGuessRules.noteDeg(r.note)?.takeIf { !r.needsChildSeat }?.let { (deg, why) ->
                if (kotlin.math.abs(backrestDeg(g.backrest) - deg) > 3) {
                    fixes += "$name ${why.removePrefix("특징: ")}"
                    g.copy(backrest = degToBackrest(deg.coerceAtMost(maxDeg(seat))), why = g.why + " · " + why)
                } else g
            } ?: g
            // 범위 밖 각도는 degToBackrest 가 잘라 버리므로 AI 답은 원래 각도로 검사
            out[seat] = if (a != null && (a.first < BACKREST_MIN_DEG || a.first > maxDeg(seat)) && !r.needsChildSeat) {
                val c = a.first.coerceIn(BACKREST_MIN_DEG, maxDeg(seat))
                fixes += "$name 등받이 ${a.first}° → ${c}° (${seatName(seat)} 주행 중 ${BACKREST_MIN_DEG}~${maxDeg(seat)}°)"
                limit(seat, name, r, g2.copy(backrest = if (g2 === g) degToBackrest(c) else g2.backrest), fixes)
            } else {
                limit(seat, name, r, g2, fixes)
            }
        }
        // 짐이 트렁크에 들어가게: 마지막 줄 자리를 앞으로 (차 TrunkEstimator: 마지막 줄 앞뒤 50 기준 ±100L)
        if (lastRowFore != null) {
            for ((seat, g) in out.toMap()) {
                if (seat == SeatLayout.DRIVER || seatRow(seat) != lastRow) continue
                if ((g.foreAft ?: 0) < lastRowFore) {
                    out[seat] = g.copy(foreAft = lastRowFore, why = g.why + " · 짐 공간")
                }
            }
        }
        // 뒤보기 카시트 바로 앞 좌석(운전석 빼고)은 카시트 공간
        for ((seat, name) in plan) {
            if (byName[name]?.need != SeatNeed.REAR_SEAT || seatRow(seat) != 2) continue
            val front = "R1_" + seat.substringAfter('_')
            val g = out[front] ?: continue
            if (front == SeatLayout.DRIVER) continue
            if ((g.foreAft ?: 0) < SeatGuessRules.ROOM_FOR_REAR_SEAT) {
                if (g.why.startsWith("AI") || g.why.startsWith("비슷한")) fixes += "${seatName(front)}을 앞뒤 ${SeatGuessRules.ROOM_FOR_REAR_SEAT}로 당김 (뒤 후방 카시트)"
                out[front] = g.copy(foreAft = SeatGuessRules.ROOM_FOR_REAR_SEAT, why = g.why + " · 뒤 후방 카시트 공간")
            }
        }
        return PostureResult(out, fixes, used)
    }
}

/** 지난 여정에서 이 사람이 내리기 전 자세 찾기 (RAG 검색 결과를 자리 하나에 맞춤) */
object PastPose {
    /** "아이2" → "아이": 이름 없이 넣은 사람은 종류로 맞춤 */
    private fun base(label: String) = label.trimEnd { it.isDigit() }

    /**
     * (앞뒤, 등받이). 같은 자리에 같은 사람 > 다른 자리의 같은 사람 > 같은 자리의 같은 종류. 등받이를 모르면 null
     */
    fun of(ride: RideRecord, name: String, seat: String): Pair<Int?, Int>? {
        val exact = ride.seats.entries.filter { it.value == name }.map { it.key }
        val kind = ride.seats.entries.filter { base(it.value) == base(name) }.map { it.key }
        val order = listOfNotNull(seat.takeIf { it in exact }) + exact + listOfNotNull(seat.takeIf { it in kind && base(name) != name })
        for (s in order.distinct()) {
            val (f, b) = ride.positions[s] ?: continue
            if (b != null) return f to b
        }
        return null
    }
}

/** [지난번처럼 앉기] 후보: 다녀온 여정 또는 차로 보낸 여정의 자리 배치 */
data class PastTrip(val at: Long, val title: String, val seats: Map<String, String>, val trip: TripDraft) {
    /** "조수석 어머니 · 2열 오른쪽 이봄" */
    val line: String get() = seats.entries.filter { it.value != "운전자" }.sortedBy { it.key }.joinToString(" · ") { (k, v) -> "${seatName(k)} $v" }
}

/** 지난 배치 → 2D 배치. 이름이 구성원과 같으면 구성원으로, "아이2" 처럼 종류만 있으면 종류로. 안전 규칙에 걸리는 자리는 뺌 */
object LayoutFromPast {
    fun build(p: PastTrip, members: List<Member>): SeatLayout {
        var l = SeatLayout()
        for ((seat, label) in p.seats.entries.sortedBy { it.key }) {
            if (label == "운전자") continue
            val placed = members.firstOrNull { it.name == label }?.let(Placed::of)
                ?: GenericRider.entries.firstOrNull { it.label == label.trimEnd { c -> c.isDigit() } }?.let { Placed(label, it.who, it.need) }
                ?: continue
            if (LayoutRules.blocked(seat, placed) == null) l = l.put(seat, placed)
        }
        // 짐: 지난 여정의 수만큼 종류 기본 크기로
        var id = System.currentTimeMillis()
        for ((n, bag) in listOf(p.trip.strollers to Bag.STROLLER, p.trip.suitcases to Bag.SUITCASE, p.trip.babyBags to Bag.BABY_BAG)) {
            repeat(n) { l = l.addTrunk(TrunkPut(id++, bag.label, bag, bag.defaultLiters)) }
        }
        return l
    }
}
