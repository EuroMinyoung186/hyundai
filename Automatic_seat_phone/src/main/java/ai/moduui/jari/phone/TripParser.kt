package ai.moduui.jari.phone

/** 휴대폰에서 만드는 여정 (차의 TripRequest 와 같은 값). adults 는 운전자를 뺀 어른. */
data class TripDraft(
    /** 목적지는 차 내비가 정함. 휴대폰에서는 비워 둠 (예전 기록 · 말에서 들은 값만) */
    val destination: String = "",
    val seniors: Int = 0,
    val adults: Int = 0,
    val children: Int = 0,
    val infants: Int = 0,
    val strollers: Int = 0,
    val suitcases: Int = 0,
    val babyBags: Int = 0,
    val caregiverNextToBaby: Boolean = true,
)

enum class Slot { SENIOR, ADULT, CHILD, INFANT, STROLLER, SUITCASE, BABY_BAG, DEST }

/** heard 말한 칸, explicit 수까지 말한 칸 ("아이 둘" 은 explicit, "아이" 는 아님) */
data class ParseResult(val draft: TripDraft, val heard: Set<Slot>, val explicit: Set<Slot> = emptySet())

/**
 * 말 한 문장 → 인원 · 짐 (디자인 1-C "어머니랑 애 둘, 유모차랑 캐리어 하나").
 *
 * LLM 이 아니라 규칙: 아는 낱말(어머니 · 애 · 유모차 …)을 찾고, 바로 뒤(또는 앞)의 수(둘 · 두 명 · 2개)를 붙임.
 * 수가 없으면 1 (부모님 · 애들 · 쌍둥이는 2). 말하지 않은 칸은 0. 결과는 화면에서 고칠 수 있음.
 */
object TripParser {
    private data class Word(val text: String, val slot: Slot, val defaultCount: Int = 1, val canonical: String? = null)

    private val words: List<Word> = buildList {
        // 목적지를 먼저 찾아 지움 ("할머니댁"의 할머니가 탑승자로 세어지지 않게)
        for (d in listOf("할머니댁", "할머니 댁", "할머니집", "할머니 집", "외갓집", "시댁", "친정")) add(Word(d, Slot.DEST, canonical = d.replace(" ", "")))
        for (d in listOf("강릉", "속초", "부산", "대전", "대구", "광주", "제주", "여수", "전주", "경주", "양양", "평창", "춘천", "서울")) add(Word(d, Slot.DEST, canonical = d))
        // 짐 (아기 짐가방이 아기보다 먼저)
        for (w in listOf("기저귀가방", "기저귀 가방", "아기 짐가방", "아기짐가방", "아기가방", "아기 가방", "짐가방")) add(Word(w, Slot.BABY_BAG))
        add(Word("유모차", Slot.STROLLER))
        for (w in listOf("캐리어", "트렁크", "여행가방", "여행 가방", "슈트케이스", "큰 가방")) add(Word(w, Slot.SUITCASE))
        // 아기 (애기가 애보다 먼저)
        for (w in listOf("갓난아기", "신생아", "아기", "애기", "아가")) add(Word(w, Slot.INFANT))
        add(Word("쌍둥이", Slot.INFANT, 2))
        // 어르신
        for (w in listOf("외할머니", "외할아버지", "친할머니", "친할아버지", "할머니", "할아버지", "어머니", "어머님", "아버지", "아버님", "장모님", "장인어른", "시어머니", "시아버지", "어르신", "노인")) add(Word(w, Slot.SENIOR))
        add(Word("부모님", Slot.SENIOR, 2))
        // 아이
        add(Word("아이들", Slot.CHILD, 2))
        add(Word("애들", Slot.CHILD, 2))
        for (w in listOf("초등학생", "유치원생", "어린이", "아이", "아들", "딸", "첫째", "둘째", "셋째", "꼬마", "애")) add(Word(w, Slot.CHILD))
        // 어른 (운전자 제외)
        for (w in listOf("와이프", "아내", "남편", "엄마", "아빠", "친구", "어른", "남동생", "여동생", "동생", "누나", "언니", "오빠", "이모", "삼촌", "고모", "형")) add(Word(w, Slot.ADULT))
    }.sortedByDescending { it.text.length }

    private val numberWords = listOf("다섯" to 5, "여섯" to 6, "하나" to 1, "한" to 1, "둘" to 2, "두" to 2, "셋" to 3, "세" to 3, "석" to 3, "넷" to 4, "네" to 4, "넉" to 4)
    private const val NUMBER_FOLLOW = " ,.명개분대이랑하과와도!?"
    private const val PARTICLES = "을를은는도가이"

    private data class Hit(val word: Word, val start: Int, val end: Int)

    /** names: 구성원 탭의 가족 이름 ("지민" → 아이). 두 글자 이상만 (한 글자는 다른 말과 겹침) */
    fun parse(sentence: String, base: TripDraft = TripDraft(), names: List<Member> = emptyList()): ParseResult? {
        val text = sentence.trim()
        if (text.isEmpty()) return null
        val masked = StringBuilder(text)
        // 사람 · 짐 낱말이 아닌데 그 글자를 품은 말 ("없애"의 애 · "애초") 은 먼저 가림
        for (w in NOT_WORDS) {
            var i = masked.indexOf(w)
            while (i >= 0) {
                for (k in i until i + w.length) masked.setCharAt(k, '#')
                i = masked.indexOf(w, i + w.length)
            }
        }
        val hits = mutableListOf<Hit>()
        val extra = names.filter { it.name.trim().length >= 2 }.map { Word(it.name.trim(), it.kind) }
        val all = if (extra.isEmpty()) words else (extra + words).sortedByDescending { it.text.length }
        for (w in all) {
            var i = masked.indexOf(w.text)
            while (i >= 0) {
                hits += Hit(w, i, i + w.text.length)
                for (k in i until i + w.text.length) masked.setCharAt(k, '#')
                i = masked.indexOf(w.text, i + w.text.length)
            }
        }
        if (hits.isEmpty()) return null
        hits.sortBy { it.start }
        val m = masked.toString()
        val consumed = BooleanArray(m.length)
        val counts = mutableMapOf<Slot, Int>()
        val counted = mutableMapOf<Hit, Int>()
        // 1) 낱말 바로 뒤의 수: "애 둘" · "캐리어 2개" · "아이가 둘"
        for (h in hits.filter { it.word.slot != Slot.DEST }) {
            var j = h.end
            if (j < m.length && m[j] in PARTICLES && (j + 1 >= m.length || m[j + 1] == ' ')) j++
            while (j < m.length && m[j] == ' ') j++
            val n = numberAt(m, j)
            if (n != null) {
                counted[h] = n.first
                for (k in j until n.second) consumed[k] = true
            }
        }
        // 2) 낱말 바로 앞의 수: "두 아이" · "두 명의 아이"
        for (h in hits.filter { it.word.slot != Slot.DEST && it !in counted }) {
            var j = h.start
            while (j > 0 && m[j - 1] == ' ') j--
            for (suffix in listOf("명의", "개의", "명")) {
                if (m.substring(0, j).endsWith(suffix)) {
                    j -= suffix.length
                    break
                }
            }
            while (j > 0 && m[j - 1] == ' ') j--
            for ((w, v) in numberWords) {
                val s = j - w.length
                if (s >= 0 && m.startsWith(w, s) && (s == 0 || m[s - 1] == ' ' || m[s - 1] == ',') && (s until j).none { consumed[it] }) {
                    counted[h] = v
                    for (k in s until j) consumed[k] = true
                    break
                }
            }
            if (h !in counted) {
                val digits = m.substring(0, j).takeLastWhile { it.isDigit() }
                if (digits.isNotEmpty()) counted[h] = digits.toInt()
            }
        }
        var destination: String? = null
        for (h in hits) {
            if (h.word.slot == Slot.DEST) {
                if (destination == null) destination = h.word.canonical
                continue
            }
            val n = counted[h] ?: h.word.defaultCount
            counts[h.word.slot] = (counts[h.word.slot] ?: 0) + n
        }
        if (destination == null) {
            Regex("([가-힣]{2,6}?)(으로|로|에)\\s*(가|갈|출발|놀러)").find(text)?.let { destination = it.groupValues[1] }
        }
        fun c(s: Slot) = (counts[s] ?: 0).coerceIn(0, 6)
        val draft = base.copy(
            destination = destination ?: base.destination,
            seniors = c(Slot.SENIOR),
            adults = c(Slot.ADULT),
            children = c(Slot.CHILD),
            infants = c(Slot.INFANT).coerceAtMost(3),
            strollers = c(Slot.STROLLER),
            suitcases = c(Slot.SUITCASE),
            babyBags = c(Slot.BABY_BAG),
        )
        val heard = counts.keys.toMutableSet()
        if (destination != null) heard += Slot.DEST
        return ParseResult(draft, heard, counted.keys.map { it.word.slot }.toSet())
    }

    /** 말로 바꾼 한 줄 ("아이 1명 추가") */
    data class Change(val slot: Slot, val before: Int, val after: Int) {
        val text: String
            get() {
                val (name, unit) = LABELS.getValue(slot)
                val d = after - before
                return if (d > 0) "$name ${d}$unit 추가" else "$name ${-d}$unit 빠짐"
            }
    }

    private val LABELS = mapOf(
        Slot.SENIOR to ("어르신" to "명"), Slot.ADULT to ("어른" to "명"), Slot.CHILD to ("아이" to "명"), Slot.INFANT to ("아기" to "명"),
        Slot.STROLLER to ("유모차" to "개"), Slot.SUITCASE to ("캐리어" to "개"), Slot.BABY_BAG to ("아기 짐가방" to "개"),
    )

    private val NOT_WORDS = listOf("없애", "애초", "애써", "애매")

    private enum class Mode { SET, ADD, REMOVE }

    private val REMOVE_WORDS = Regex(
        "빼|빠져|빠지|취소|제거|삭제|지워|지우|없애|없어|없이|필요 ?없|줄여|줄이|제외|두고|놓고" +
            "|안 ?가|안 ?타|안 ?와|못 ?가|못 ?타|못 ?와|안 ?실|안 ?싣|안 ?가져|안 ?챙",
    )

    /** "다 빼" · "전부 빼" → 그 칸을 0 으로 (수를 말했으면 그 수만큼) */
    private val ALL_WORDS = Regex("""(^|\s)(다|전부|모두|싹|몽땅)(\s|$)""")

    private fun mode(text: String): Mode = when {
        REMOVE_WORDS.containsMatchIn(text) -> Mode.REMOVE
        Regex("""추가|(^|\s)더($|\s|[.!?요해])|도 (타|가|싣|실|같이|와)""").containsMatchIn(text) -> Mode.ADD
        else -> Mode.SET
    }

    fun count(d: TripDraft, s: Slot) = when (s) {
        Slot.SENIOR -> d.seniors
        Slot.ADULT -> d.adults
        Slot.CHILD -> d.children
        Slot.INFANT -> d.infants
        Slot.STROLLER -> d.strollers
        Slot.SUITCASE -> d.suitcases
        Slot.BABY_BAG -> d.babyBags
        Slot.DEST -> 0
    }

    fun with(d: TripDraft, s: Slot, n: Int) = when (s) {
        Slot.SENIOR -> d.copy(seniors = n)
        Slot.ADULT -> d.copy(adults = n)
        Slot.CHILD -> d.copy(children = n)
        Slot.INFANT -> d.copy(infants = n.coerceAtMost(3))
        Slot.STROLLER -> d.copy(strollers = n)
        Slot.SUITCASE -> d.copy(suitcases = n)
        Slot.BABY_BAG -> d.copy(babyBags = n)
        Slot.DEST -> d
    }

    /**
     * 말 한 문장을 지금 여정에 반영. "아이 한 명 추가" · "캐리어 빼 줘" · "어머니랑 애 둘".
     * 추가 · 더 → 더하기, 빼 · 취소 → 빼기, 그 밖에는 말한 칸만 그 수로 맞춤 (말하지 않은 칸은 그대로).
     * 목적지는 차 내비에서 오므로 말에서 받지 않음. 반환: 바뀐 여정과 바뀐 칸들 (아무것도 못 알아들으면 null).
     */
    fun apply(sentence: String, base: TripDraft, names: List<Member> = emptyList()): Pair<TripDraft, List<Change>>? {
        val r = parse(sentence, base, names) ?: return null
        val slots = r.heard - Slot.DEST
        if (slots.isEmpty()) return null
        val m = mode(sentence)
        val all = m == Mode.REMOVE && ALL_WORDS.containsMatchIn(sentence)
        var d = base
        for (s in Slot.entries.filter { it in slots }) {
            val said = count(r.draft, s)
            val now = count(base, s)
            d = with(d, s, when (m) {
                Mode.SET -> said
                Mode.ADD -> now + said
                Mode.REMOVE -> if (all && s !in r.explicit) 0 else now - said
            }.coerceIn(0, 6))
        }
        val changes = Slot.entries.filter { it != Slot.DEST && count(base, it) != count(d, it) }.map { Change(it, count(base, it), count(d, it)) }
        return d to changes
    }

    /** s[i] 에서 시작하는 수. 반환: (값, 끝 위치). "둘째" · "세트" · "한번" 같은 낱말은 수로 보지 않음. */
    private fun numberAt(s: String, i: Int): Pair<Int, Int>? {
        if (i >= s.length) return null
        if (s[i].isDigit()) {
            var e = i
            while (e < s.length && s[e].isDigit()) e++
            return s.substring(i, e).toInt() to e
        }
        for ((w, v) in numberWords) {
            if (s.startsWith(w, i)) {
                val e = i + w.length
                if (e >= s.length || s[e] in NUMBER_FOLLOW) return v to e
            }
        }
        return null
    }
}
