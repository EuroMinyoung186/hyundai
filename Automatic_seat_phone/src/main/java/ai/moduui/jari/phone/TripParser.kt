package ai.moduui.jari.phone

/** 휴대폰에서 만드는 여정 (차의 TripRequest 와 같은 값). adults 는 운전자를 뺀 어른. */
data class TripDraft(
    val destination: String = "강릉",
    val seniors: Int = 0,
    val adults: Int = 0,
    val children: Int = 0,
    val infants: Int = 1,
    val strollers: Int = 0,
    val suitcases: Int = 0,
    val babyBags: Int = 0,
    val caregiverNextToBaby: Boolean = true,
)

enum class Slot { SENIOR, ADULT, CHILD, INFANT, STROLLER, SUITCASE, BABY_BAG, DEST }

data class ParseResult(val draft: TripDraft, val heard: Set<Slot>)

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

    fun parse(sentence: String, base: TripDraft = TripDraft()): ParseResult? {
        val text = sentence.trim()
        if (text.isEmpty()) return null
        val masked = StringBuilder(text)
        val hits = mutableListOf<Hit>()
        for (w in words) {
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
        return ParseResult(draft, heard)
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
