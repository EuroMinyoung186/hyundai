package ai.moduui.jari.phone

/**
 * LLM 좌석 배치 + 규칙 검사.
 *
 *  1) 프롬프트에 규칙을 같이 줌 (PlanPrompt)
 *  2) LLM 이 "좌석=이름" 으로 배치안을 냄 (PlanParse)
 *  3) 규칙이 검사해서 통과 · 경고 · 제외로 나눔 (PlanRules.check). 안전 규칙을 어긴 안은 절대 쓰지 않음
 *  4) 제외되면 이유를 알려 주고 한 번 더, 그래도 안 되면 규칙으로 만든 배치 (PlanRules.fallback)
 */

/** 이번에 탈 사람 한 명 */
data class Rider(val label: String, val who: Who, val need: SeatNeed?, val note: String = "", val driver: Boolean = false) {
    val needsChildSeat: Boolean get() = need == SeatNeed.REAR_SEAT || need == SeatNeed.CAR_SEAT || need == SeatNeed.BOOSTER
    val isBaby: Boolean get() = need == SeatNeed.REAR_SEAT || need == SeatNeed.CAR_SEAT
    val canCare: Boolean get() = !driver && (who == Who.ADULT || who == Who.SENIOR) && need == null

    /** 프롬프트 한 줄: "이봄 (아기, 뒤보기 카시트, 기타: 잘 잠)" */
    val describe: String
        get() = buildString {
            append(label).append(" (").append(if (driver) "운전자" else who.label)
            need?.let { append(", ").append(it.label) }
            if (note.isNotBlank()) append(", 기타: ").append(note)
            append(")")
        }
}

/** 받침에 맞는 조사: "어르신을" · "이봄이" · "지민은" (영어 · 숫자는 뒤 조사 그대로) */
fun String.josa(withFinal: String, without: String): String {
    val c = lastOrNull() ?: return this + without
    if (c !in '가'..'힣') return this + without
    return this + if ((c - '가') % 28 != 0) withFinal else without
}

enum class IssueLevel { HARD, SOFT }

data class PlanIssue(val level: IssueLevel, val text: String)

/**
 * 배치안 하나와 규칙 검사 결과. source: "AI" · "AI 다시" · "AI + 규칙 보완" · "규칙".
 * fixes: 규칙이 AI 안에서 고친 것 ("어르신을 조수석에 넣음")
 */
data class CheckedPlan(
    val seats: Map<String, String>,
    val issues: List<PlanIssue>,
    val source: String,
    val raw: String = "",
    val fixes: List<String> = emptyList(),
    /** 평소 자리와 맞는 사람 수 (맞음, 기억 있는 사람). 기억이 없으면 null */
    val usual: Pair<Int, Int>? = null,
) {
    val passed: Boolean get() = issues.none { it.level == IssueLevel.HARD }
    val warnings: List<PlanIssue> get() = issues.filter { it.level == IssueLevel.SOFT }
}

fun seatRow(id: String): Int = id.getOrNull(1)?.digitToIntOrNull() ?: 9

fun seatName(id: String): String {
    val row = seatRow(id)
    val side = when {
        id.endsWith("LEFT") -> "왼쪽"
        id.endsWith("RIGHT") -> "오른쪽"
        else -> "가운데"
    }
    return when (id) {
        "R1_LEFT" -> "운전석"
        "R1_RIGHT" -> "조수석"
        else -> "${row}열 $side"
    }
}

/** 여정 + 구성원 → 탈 사람 목록 (운전자 포함). 이름으로 넣은 사람은 이름으로, 나머지는 "아이2" 처럼 */
fun ridersOf(d: TripDraft, picked: List<Long>, members: List<Member>): List<Rider> = buildList {
    add(Rider("운전자", Who.ADULT, null, driver = true))
    val slots = listOf(
        Triple(Slot.SENIOR, "어르신", Who.SENIOR), Triple(Slot.ADULT, "어른", Who.ADULT),
        Triple(Slot.CHILD, "아이", Who.CHILD), Triple(Slot.INFANT, "아기", Who.BABY),
    )
    for ((slot, generic, who) in slots) {
        val n = TripParser.count(d, slot)
        val named = picked.mapNotNull { id -> members.firstOrNull { it.id == id && it.kind == slot } }.take(n)
        for (m in named) add(Rider(m.name, m.who, m.need, m.note))
        val need = when (slot) {
            Slot.INFANT -> SeatNeed.CAR_SEAT
            Slot.CHILD -> SeatNeed.BOOSTER
            else -> null
        }
        for (i in 1..(n - named.size)) add(Rider(if (n - named.size == 1) generic else "$generic$i", who, need))
    }
}

object PlanPrompt {
    /** LLM 에게 주는 규칙 (PlanRules.check 와 같은 내용) */
    val RULES = listOf(
        "운전석 R1_LEFT 에는 운전자가 이미 앉았다. 다른 사람은 R1_LEFT 에 앉히지 않는다.",
        "모든 사람은 정확히 한 자리, 한 자리에는 한 사람.",
        "카시트·부스터가 필요한 아이는 1열(R1_)에 앉히지 않는다. 조수석 에어백 때문이다.",
        "아기(카시트) 옆 같은 줄에 보호자 어른을 앉힌다.",
        "어르신은 타고 내리기 쉬운 R1_RIGHT 나 2열에 앉힌다.",
        "멀미하는 사람은 앞쪽 줄에 앉힌다.",
        "[평소: ...] 가 있는 사람은 규칙에 맞으면 평소 자리에 앉힌다. 비슷한 지난 여정이 있으면 그 배치를 따른다.",
    )

    /**
     * 시스템 지시: 매번 같은 부분 (규칙 · 답 형식 · 예시). 대화를 만들 때 미리 계산해 KV 캐시로 재사용.
     */
    val SYSTEM: String = buildString {
        appendLine("너는 차 좌석 배치 도우미다. 규칙을 지켜 사람마다 좌석 하나를 정한다.")
        appendLine("규칙:")
        RULES.forEachIndexed { i, r -> appendLine("${i + 1}. $r") }
        appendLine("답은 '답할 사람' 에 나온 사람마다 한 줄씩 이름=좌석 으로만 쓴다.")
        appendLine("답할 사람에 없는 이름, 빈 좌석, 설명, 생각은 쓰지 않는다.")
        appendLine("사람마다 [가능: ...] 안의 좌석 중에서 고르고, 두 사람에게 같은 좌석을 주지 않는다.")
        appendLine("보기:")
        appendLine("빈 좌석: R1_RIGHT, R2_LEFT, R2_RIGHT")
        appendLine("사람: A (어르신) [가능: R1_RIGHT, R2_LEFT, R2_RIGHT] [평소: R2_LEFT] / B (아기, 카시트) [가능: R2_LEFT, R2_RIGHT]")
        appendLine("답할 사람: A, B")
        appendLine("이때 답은 아래 두 줄뿐이다:")
        appendLine("A=R2_LEFT")
        append("B=R2_RIGHT")
    }

    /** 요청마다 바뀌는 부분: 좌석 · 사람 · 사람마다 앉을 수 있는 좌석 (규칙으로 미리 거름) */
    fun build(riders: List<Rider>, seats: List<String>, memory: PlanMemory = PlanMemory()): String = buildString {
        // 운전자는 규칙으로 R1_LEFT 고정 → AI 는 나머지 사람만 정함
        val ask = riders.filter { !it.driver }
        appendLine("빈 좌석: ${seats.filter { it != "R1_LEFT" }.joinToString(", ")}")
        val prefer = memory.prefer(riders)
        append("사람: ").append(
            ask.joinToString(" / ") { r ->
                val ok = PlanRules.allowed(r, seats)
                val usual = prefer[r.label].orEmpty().filter { it in ok }.take(2)
                "${r.describe} [가능: ${ok.joinToString(", ")}]" + if (usual.isEmpty()) "" else " [평소: ${usual.joinToString(", ")}]"
            },
        )
        // 아주 비슷한 지난 여정은 배치를 그대로 보여 줌 (지금 탈 사람만)
        memory.similar.firstOrNull()?.takeIf { it.score >= 70 }?.let { s ->
            val names = ask.map { it.label }.toSet()
            val past = s.ride.seats.filter { (seat, n) -> n in names && seat in seats }
            if (past.isNotEmpty()) {
                appendLine()
                append("비슷한 지난 여정 (${s.score}%): ").append(past.entries.joinToString(", ") { (k, v) -> "$v=$k" })
            }
        }
        appendLine()
        append("답할 사람: ").append(ask.joinToString(", ") { it.label })
    }

    fun retry(issues: List<PlanIssue>, riders: List<Rider>, seats: List<String>): String = buildString {
        append("규칙 위반: ")
        append(issues.filter { it.level == IssueLevel.HARD }.joinToString(" / ") { it.text })
        append(". 가능한 좌석: ")
        append(riders.filter { !it.driver }.joinToString(" / ") { "${it.label} ${PlanRules.allowed(it, seats).joinToString(",")}" })
        append(". 답할 사람마다 이름=좌석 한 줄로 다시 써.")
    }
}

object PlanParse {
    private val SEAT = """R[1-3]_(?:LEFT|RIGHT|CENTER)|운전석|조수석|[1-3]열\s*(?:왼쪽|오른쪽|가운데)"""
    private val SEAT_RE = Regex(SEAT)

    private fun seatId(s: String): String = when {
        s.startsWith("R") -> s
        s == "운전석" -> "R1_LEFT"
        s == "조수석" -> "R1_RIGHT"
        else -> {
            val row = s.first()
            val side = when {
                "왼" in s -> "LEFT"
                "오" in s -> "RIGHT"
                else -> "CENTER"
            }
            "R${row}_$side"
        }
    }

    /**
     * LLM 답 → 좌석별 사람. "이봄=R2_RIGHT" · "R2_RIGHT=이봄" · "조수석: 어머니" · "2열 왼쪽 - 지민" 모두 받음.
     * 운전석은 운전자로 고정. 탈 사람 목록에 없는 이름(예시를 따라 쓴 이름 등)은 버리고, 같은 사람이 또 나오면 처음 것만.
     */
    fun parse(text: String, riders: List<Rider>): Map<String, String> {
        val out = linkedMapOf("R1_LEFT" to "운전자")
        val placed = mutableSetOf<String>()
        for (part in text.split('\n', ',', '/', '|')) {
            val m = SEAT_RE.find(part) ?: continue
            val seat = seatId(m.value.trim())
            val said = part.removeRange(m.range).replace(Regex("[=:→>\\-]"), " ").trim()
                .substringBefore("(").trim().trimEnd('.', ')').trim()
            if (said.isEmpty()) continue
            val name = riders.firstOrNull { it.label == said }?.label
                ?: riders.firstOrNull { said.startsWith(it.label) || it.label.startsWith(said) }?.label
                ?: continue
            // 같은 사람이 또 나오거나, 이미 다른 사람이 받은 좌석이면 처음 것만 (빠진 사람은 규칙이 채움)
            if (name == "운전자" || name in placed || seat in out) continue
            placed += name
            out[seat] = name
        }
        return out
    }
}

object PlanRules {
    /** 이 사람이 앉아도 되는 좌석 (HARD 규칙 기준). 운전자는 운전석만, 카시트 · 부스터 아이는 1열 빼고 */
    fun allowed(r: Rider, seats: List<String>): List<String> = when {
        r.driver -> listOf("R1_LEFT")
        r.needsChildSeat -> seats.filter { seatRow(it) >= 2 }
        else -> seats.filter { it != "R1_LEFT" }
    }

    /** 배치안 검사. HARD 는 안전 · 기본 규칙 (어기면 제외), SOFT 는 권장 (경고만) */
    fun check(seats: Map<String, String>, riders: List<Rider>, carSeats: List<String>, luggageLiters: Int = 0, trunkLiters: Int = 0): List<PlanIssue> = buildList {
        fun hard(t: String) = add(PlanIssue(IssueLevel.HARD, t))
        fun soft(t: String) = add(PlanIssue(IssueLevel.SOFT, t))
        val byName = riders.associateBy { it.label }
        if (riders.size > carSeats.size) hard("자리가 ${carSeats.size}개인데 ${riders.size}명이에요")
        for ((seat, name) in seats) {
            if (seat !in carSeats) hard("이 차에는 ${seatName(seat)}($seat) 자리가 없어요")
            if (byName[name] == null) hard("탈 사람 목록에 없는 '$name'")
        }
        if (seats["R1_LEFT"] != "운전자") hard("운전석에는 운전자가 앉아야 해요")
        for (r in riders) {
            val at = seats.filterValues { it == r.label }.keys
            when {
                at.isEmpty() -> hard("${r.label} 자리가 없어요")
                at.size > 1 -> hard("${r.label.josa("이", "가")} 두 자리에 있어요")
            }
            val seat = at.firstOrNull() ?: continue
            val row = seatRow(seat)
            if (r.needsChildSeat && row == 1) hard("${r.label.josa("은", "는")} 앞좌석에 앉으면 안 돼요 (${r.need?.label}, 에어백)")
            if (r.who == Who.SENIOR && row >= 3) soft("${r.label.josa("은", "는")} 3열이라 타고 내리기 힘들어요")
            if ((r.note.contains("멀미")) && row >= 3) soft("${r.label.josa("은", "는")} 멀미가 있는데 3열이에요")
            if (r.isBaby) {
                val careNear = seats.filter { (s, n) -> seatRow(s) == row && s != seat && byName[n]?.canCare == true }
                if (careNear.isEmpty()) soft("${r.label} 옆에 보호자 어른이 없어요")
            }
        }
        if (trunkLiters > 0 && luggageLiters > trunkLiters) soft("짐 ${luggageLiters}L가 트렁크 약 ${trunkLiters}L보다 커요")
    }

    /**
     * AI 안 보완: AI 가 정한 자리는 두고, 규칙을 어긴 자리 · 겹친 자리 · 없는 사람만 빼고,
     * 빠진 사람을 앉아도 되는 빈자리에 넣음. 반환: (고친 안, 고친 것 설명)
     */
    fun complete(plan: Map<String, String>, riders: List<Rider>, carSeats: List<String>): Pair<Map<String, String>, List<String>> {
        val byName = riders.associateBy { it.label }
        val out = linkedMapOf<String, String>()
        val fixes = mutableListOf<String>()
        for ((seat, name) in plan) {
            val r = byName[name]
            when {
                r == null -> fixes += "목록에 없는 '$name' 뺌"
                seat !in carSeats -> fixes += "없는 자리(${seat})의 $name 뺌"
                seat !in allowed(r, carSeats) -> fixes += "${name.josa("을", "를")} ${seatName(seat)}에서 뺌 (규칙)"
                out.containsValue(name) -> fixes += "${name.josa("이", "가")} 두 번 있어 하나 뺌"
                else -> out[seat] = name
            }
        }
        val order = listOf("R1_LEFT", "R2_RIGHT", "R2_LEFT", "R1_RIGHT", "R2_CENTER", "R3_RIGHT", "R3_LEFT", "R3_CENTER")
        // 카시트 아기 · 부스터 아이부터 (앉을 수 있는 자리가 적음)
        for (r in riders.sortedBy { if (it.needsChildSeat) 0 else 1 }) {
            if (out.containsValue(r.label)) continue
            val ok = allowed(r, carSeats)
            val seat = order.filter { it in ok }.firstOrNull { it !in out } ?: ok.firstOrNull { it !in out } ?: continue
            out[seat] = r.label
            fixes += "${r.label.josa("을", "를")} ${seatName(seat)}에 넣음"
        }
        return out to fixes
    }

    /** 규칙으로 만든 배치 (LLM 이 없거나 다 제외됐을 때). 항상 같은 입력 → 같은 결과 */
    fun fallback(riders: List<Rider>, carSeats: List<String>, prefer: Map<String, List<String>> = emptyMap()): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val free = carSeats.toMutableList()
        fun take(seat: String, r: Rider): Boolean {
            if (seat !in free) return false
            free -= seat
            out[seat] = r.label
            return true
        }
        val rearOrder = listOf("R2_RIGHT", "R2_LEFT", "R2_CENTER", "R3_RIGHT", "R3_LEFT", "R3_CENTER").filter { it in carSeats }
        val left = riders.toMutableList()
        left.firstOrNull { it.driver }?.let { if (take("R1_LEFT", it)) left -= it }
        // 기억: 평소 자리 · 비슷한 지난 여정 자리부터 (규칙이 허락하는 자리만). 앉을 곳이 적은 아이부터
        for (r in left.filter { it.label in prefer }.sortedBy { if (it.needsChildSeat) 0 else 1 }) {
            val ok = allowed(r, carSeats)
            prefer[r.label].orEmpty().firstOrNull { it in ok && it in free }?.let { take(it, r); left -= r }
        }
        // 카시트 아기 → 부스터 아이 순서로 뒷좌석 (보도 쪽 2열 오른쪽부터)
        for (r in left.filter { it.isBaby } + left.filter { it.needsChildSeat && !it.isBaby }) {
            rearOrder.firstOrNull { it in free }?.let { take(it, r); left -= r }
        }
        // 첫 아기와 같은 줄에 보호자
        val babySeat = out.entries.firstOrNull { (_, n) -> riders.first { it.label == n }.isBaby }?.key
        val carer = left.filter { it.canCare }.sortedBy { if (it.who == Who.ADULT) 0 else 1 }.firstOrNull()
        if (babySeat != null && carer != null) {
            free.firstOrNull { seatRow(it) == seatRow(babySeat) }?.let { take(it, carer); left -= carer }
        }
        // 어르신은 조수석 먼저, 그다음 2열
        for (r in left.filter { it.who == Who.SENIOR }) {
            (listOf("R1_RIGHT") + rearOrder).firstOrNull { it in free }?.let { take(it, r); left -= r }
        }
        for (r in left.toList()) {
            (listOf("R1_RIGHT") + rearOrder).firstOrNull { it in free }?.let { take(it, r); left -= r }
        }
        return out
    }
}
