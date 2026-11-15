package ai.moduui.jari.phone

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** 구성원이 어떤 사람인지 (화면 표시용) */
enum class Who(val label: String) { SENIOR("어르신"), ADULT("어른"), CHILD("아이"), BABY("아기") }

/** 아이 · 아기에게 필요한 좌석 장치. 나이 · 키로 고르고, 모르면 종류 기본값 */
enum class SeatNeed(val label: String) {
    REAR_SEAT("뒤보기 카시트"),
    CAR_SEAT("카시트"),
    BOOSTER("부스터 시트"),
    BELT("안전띠만"),
}

/** 가진 카시트를 어떻게 다는지 (구성원에서 고름). need: 그렇게 달면 필요한 좌석 장치 */
enum class CarSeatDir(val label: String, val need: SeatNeed) {
    REAR("후방", SeatNeed.REAR_SEAT),
    FRONT("전방", SeatNeed.CAR_SEAT),
    BOOSTER("부스터", SeatNeed.BOOSTER),
}

/**
 * 카시트 단계 기준 (2026-11-13 조사).
 *  - 법: 도로교통법 제50조 1항 · 제11조 1항 — 6세 미만 영유아는 유아보호용 장구 필수.
 *  - 뒤보기: UN R129(i-Size) 15개월까지 의무, 76cm 미만이면 계속 뒤보기 (gov.uk child-car-seats-the-rules).
 *  - 하네스 앞보기: R129 제품 76~105cm. AAP 는 하네스를 최소 4세까지 권장.
 *  - 부스터: 하이백 100~150cm (R129). 안전띠만: AAP · 호주 RACT 145cm, 영국 법 12세 또는 135cm.
 */
object SeatRule {
    /** 이보다 어리면 뒤보기 (개월) */
    const val REAR_MONTHS = 15

    /** 이보다 작으면 뒤보기 (cm) */
    const val REAR_HEIGHT = 76

    /** 이보다 작으면 하네스 카시트, 이상이면 부스터 (cm) */
    const val BOOSTER_FROM_HEIGHT = 105

    /** 키를 모를 때: 이보다 어리면 하네스 카시트 (개월, AAP 4세) */
    const val BOOSTER_FROM_MONTHS = 48

    /** 이 키부터 안전띠만 (cm, AAP 145) */
    const val BELT_HEIGHT = 145

    /** 키를 모를 때: 이 나이부터 안전띠만 (개월, 영국 법 12세) */
    const val BELT_MONTHS = 12 * 12

    /** 이보다 어리면 법정 의무 (개월, 도로교통법 6세 미만) */
    const val LEGAL_MONTHS = 6 * 12

    /** 키 · 나이를 모르고 몸무게만 알 때 (R44 그룹: 0+ ~13kg, 1 ~18kg, 2·3 ~36kg) */
    const val REAR_KG = 13
    const val BOOSTER_FROM_KG = 18
    const val BELT_KG = 36
}

/**
 * 구성원 탭에 적어 둔 사람 (운전자 빼고). 이름 말고는 몰라도 됨.
 * kind 는 여정 칸 (아기·카시트 → INFANT, 부스터 → CHILD, 안전띠 → ADULT/SENIOR).
 * 카시트 단계는 키 > 나이 > 몸무게 순으로 봄.
 */
data class Member(
    val id: Long,
    val name: String,
    val who: Who,
    val ageMonths: Int? = null,
    val heightCm: Int? = null,
    val weightKg: Int? = null,
    /** 기타 사항 (멀미 · 창가 선호 등). 차 계획에는 안 쓰고 보여 주기만 */
    val note: String = "",
    /** 카시트가 있는지: null 모름 · true 있음 · false 없음 */
    val hasCarSeat: Boolean? = null,
    /** 카시트를 다는 방향 (있을 때) */
    val carSeatDir: CarSeatDir? = null,
    /** 카시트 기울기 (°, 있을 때) */
    val carSeatAngle: Int? = null,
    /** 수유 간격 (분). 아기만, 모르면 null */
    val feedMin: Int? = null,
    /** 아기 밥(이유식) 시각들 (하루 중 분, 예: 720 = 12:00). 아기만, 비면 밥 휴게소 제안 안 함 */
    val mealMins: List<Int> = emptyList(),
) {
    /** 아이에게 필요한 좌석 장치: 카시트를 직접 정했으면 그대로, 아니면 나이 · 키 · 몸무게로 */
    val need: SeatNeed?
        get() = carSeatDir?.takeIf { hasCarSeat == true && (who == Who.BABY || who == Who.CHILD) }?.need ?: suggested

    /** 나이 · 키 · 몸무게로 고른 좌석 장치 (기준: SeatRule) */
    val suggested: SeatNeed?
        get() {
            if (who == Who.SENIOR || who == Who.ADULT) return null
            val m = ageMonths
            val h = heightCm
            val w = weightKg.takeIf { h == null && m == null }
            return when {
                (m != null && m < SeatRule.REAR_MONTHS) || (h != null && h < SeatRule.REAR_HEIGHT) -> SeatNeed.REAR_SEAT
                w != null && w < SeatRule.REAR_KG -> SeatNeed.REAR_SEAT
                h != null && h < SeatRule.BOOSTER_FROM_HEIGHT -> SeatNeed.CAR_SEAT
                h == null && m != null && m < SeatRule.BOOSTER_FROM_MONTHS -> SeatNeed.CAR_SEAT
                w != null && w < SeatRule.BOOSTER_FROM_KG -> SeatNeed.CAR_SEAT
                h != null && h < SeatRule.BELT_HEIGHT -> SeatNeed.BOOSTER
                h == null && m != null && m < SeatRule.BELT_MONTHS -> SeatNeed.BOOSTER
                w != null && w < SeatRule.BELT_KG -> SeatNeed.BOOSTER
                h != null || m != null || w != null -> SeatNeed.BELT
                who == Who.BABY -> SeatNeed.CAR_SEAT
                else -> SeatNeed.BOOSTER
            }
        }

    val kind: Slot
        get() = when (need) {
            SeatNeed.REAR_SEAT, SeatNeed.CAR_SEAT -> Slot.INFANT
            SeatNeed.BOOSTER -> Slot.CHILD
            SeatNeed.BELT -> Slot.ADULT
            null -> if (who == Who.SENIOR) Slot.SENIOR else Slot.ADULT
        }

    /** 6세 미만이라 카시트 · 부스터가 법으로 필수 (나이를 모르면 판단 안 함) */
    val legalSeat: Boolean
        get() = ageMonths != null && ageMonths < SeatRule.LEGAL_MONTHS && need != SeatNeed.BELT

    /** 카시트 한 줄: "후방 카시트 40°" · "카시트 없음" · (모르면) 필요한 장치 */
    val seatText: String?
        get() = when {
            hasCarSeat == true && carSeatDir != null ->
                (if (carSeatDir == CarSeatDir.BOOSTER) "부스터" else "${carSeatDir.label} 카시트") + (carSeatAngle?.let { " ${it}°" } ?: "")
            hasCarSeat == false && need != null && need != SeatNeed.BELT -> "카시트 없음"
            else -> need?.label
        }

    /** 카시트 설정 확인할 점 (R129 · 도로교통법 기준) */
    val carSeatWarnings: List<String>
        get() = buildList {
            val m = ageMonths
            val h = heightCm
            if (hasCarSeat == false && suggested != null && suggested != SeatNeed.BELT) {
                add(if (legalSeat) "6세 미만은 카시트가 법으로 필요해요" else "${suggested!!.label}가 필요해요")
            }
            if (hasCarSeat == true && carSeatDir == CarSeatDir.FRONT && ((m != null && m < SeatRule.REAR_MONTHS) || (h != null && h < SeatRule.REAR_HEIGHT))) {
                add("15개월 · 76cm 전에는 후방으로 달아야 해요")
            }
            if (hasCarSeat == true && carSeatDir == CarSeatDir.BOOSTER && h != null && h < 100) add("부스터는 키 100cm부터예요")
        }

    /** "아이 · 5살 · 110cm · 부스터 시트 · 법정 의무" */
    val detail: String
        get() = listOfNotNull(
            who.label,
            ageMonths?.let { if (it < 24) "${it}개월" else "${it / 12}살" },
            heightCm?.let { "${it}cm" },
            weightKg?.let { "${it}kg" },
            seatText,
            if (legalSeat) "법정 의무" else null,
            feedMin?.let { "수유 ${feedText(it)}마다" },
        ).joinToString(" · ")
}

/** 수유 간격 글: 180 → "3시간", 150 → "2시간 30분" */
fun feedText(min: Int): String = listOfNotNull(
    (min / 60).takeIf { it > 0 }?.let { "${it}시간" },
    (min % 60).takeIf { it > 0 }?.let { "${it}분" },
).joinToString(" ")

/**
 * 짐 종류: 여정 칸과, 크기를 모를 때 쓰는 기본 부피(L). 접은 외곽 치수를 곱한 박스 부피 (2026-11-13 조사).
 * 휴대용 = Bugaboo Butterfly 54×45×23 ≈ 56L, 절충형 = Nuna TRIV 125L, 디럭스 = Bugaboo Fox 일체 접이 243L,
 * 쌍둥이 = Bugaboo Donkey 310L, 기저귀 가방 = Bugaboo 19~22L, 아이스박스 = 26~45L 내부 → 외곽 약 1.5배, 휴대용 침대 = BabyBjörn 41L.
 */
enum class Bag(val label: String, val slot: Slot, val defaultLiters: Int) {
    STROLLER_COMPACT("휴대용 유모차", Slot.STROLLER, 55),
    STROLLER("유모차", Slot.STROLLER, 125),
    STROLLER_DELUXE("디럭스 유모차", Slot.STROLLER, 240),
    STROLLER_TWIN("쌍둥이 유모차", Slot.STROLLER, 310),
    CARRY_ON("기내용 캐리어", Slot.SUITCASE, 40),
    SUITCASE("캐리어", Slot.SUITCASE, 90),
    BABY_BAG("아기 짐가방", Slot.BABY_BAG, 20),
    COOLER("아이스박스", Slot.SUITCASE, 40),
    CRIB("휴대용 아기침대", Slot.SUITCASE, 40),
    OTHER("짐", Slot.SUITCASE, 30),
}

/** 크기를 몰라도 대충 고르는 칩. 종류 기본 부피에 곱함 (유모차 125L → 크게 약 190L) */
enum class RoughSize(val label: String, val factor: Double) {
    SMALL("작게", 0.5),
    NORMAL("보통", 1.0),
    LARGE("크게", 1.5),
    XLARGE("아주 크게", 2.0),
    ;

    fun liters(bag: Bag): Int = (bag.defaultLiters * factor).roundToInt().coerceIn(5, 400)
}

/** 무게를 대충 고르는 칩. kg 는 캐리어 부피 어림에만 씀 (차에는 보내지 않음) */
enum class RoughWeight(val label: String, val kg: Int) {
    LIGHT("가벼움", 5),
    NORMAL("보통 무게", 12),
    HEAVY("무거움", 22),
}

/** 구성원 탭에 적어 둔 짐. 크기 · 무게는 몰라도 됨 (모르면 종류 기본 부피) */
data class LuggageItem(
    val id: Long,
    val name: String,
    val bag: Bag,
    val liters: Int? = null,
    val inches: Int? = null,
    val weightKg: Int? = null,
    /** 기타 사항 (깨지기 쉬움 · 먼저 꺼냄 등) */
    val note: String = "",
) {
    val kind: Slot get() = bag.slot

    /** 부피 어림: 적은 리터 · 가로세로높이 > 인치 > 무게 > 종류 기본값 */
    val estLiters: Int
        get() = liters
            ?: inches?.let { inchLiters(it) }
            ?: weightKg?.takeIf { bag.slot == Slot.SUITCASE }?.let { (it * 4).coerceIn(30, 120) }
            ?: bag.defaultLiters

    val sizeKnown: Boolean get() = liters != null || inches != null || (weightKg != null && bag.slot == Slot.SUITCASE)

    /** "캐리어 · 24인치 · 약 65L · 18kg" */
    val detail: String
        get() = listOfNotNull(
            bag.label,
            inches?.let { "${it}인치" },
            "약 ${estLiters}L" + if (sizeKnown) "" else " (기본값)",
            weightKg?.let { "${it}kg" },
        ).joinToString(" · ")

    companion object {
        /** 캐리어 인치 → 부피(L). Samsonite C-Lite 55/69/75/81cm = 36~42/68/94/123L, RIMOWA Check-In M 60L 기준 */
        private val INCH_TABLE = listOf(18 to 30, 20 to 40, 22 to 50, 24 to 60, 26 to 70, 28 to 90, 30 to 120, 32 to 135)

        fun inchLiters(inch: Int): Int {
            val t = INCH_TABLE
            if (inch <= t.first().first) return t.first().second
            if (inch >= t.last().first) return t.last().second
            val i = t.indexOfFirst { it.first >= inch }
            val (a, la) = t[i - 1]
            val (b, lb) = t[i]
            return (la + (lb - la) * (inch - a).toFloat() / (b - a)).roundToInt()
        }
    }
}

/**
 * 구성원 탭에서 적은 한 줄 → 사람 · 짐 (규칙. LLM 없음).
 * 사람: "지민 5살 110cm" · "둘째 18개월" · "어머니". 짐: "24인치 캐리어 18kg" · "디럭스 유모차" · "아이스박스 60x40x40".
 */
object PresetParser {
    private val SENIOR = listOf("할머니", "할아버지", "어머니", "어머님", "아버지", "아버님", "장모", "장인", "시어머니", "시아버지", "어르신", "외할", "친할")
    private val BABY = listOf("아기", "애기", "아가", "신생아", "갓난", "쌍둥이")
    private val CHILD = listOf("아이", "아들", "딸", "첫째", "둘째", "셋째", "막내", "초등", "유치원", "어린이", "꼬마", "조카")
    private val KO_NUM = mapOf("한" to 1, "두" to 2, "세" to 3, "네" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9, "열" to 10)

    private val MONTHS = Regex("""(\d{1,2})\s*개월""")
    private val YEARS = Regex("""(?:만\s*)?(\d{1,2})\s*(살|세)""")
    private val YEARS_KO = Regex("""(다섯|여섯|일곱|여덟|아홉|열|한|두|세|네)\s*살""")
    private val HEIGHT = Regex("""(?:키\s*)?(\d{2,3})\s*(cm|센티|센치|㎝)""", RegexOption.IGNORE_CASE)
    private val HEIGHT_KEY = Regex("""키\s*(\d{2,3})""")
    private val KG = Regex("""(\d{1,3})(?:\.\d)?\s*(kg|키로|킬로)""", RegexOption.IGNORE_CASE)
    private val INCH = Regex("""(\d{2})\s*(인치|"|inch)""", RegexOption.IGNORE_CASE)
    private val LITERS = Regex("""(\d{2,3})\s*(L|ℓ|리터)(?![a-zA-Z])""")
    private val DIMS = Regex("""(\d{2,3})\s*[x×*]\s*(\d{2,3})\s*[x×*]\s*(\d{2,3})""")

    /** 정수 하나 ("110", "110cm", "18kg") */
    private fun num(s: String): Int? = Regex("""\d{1,3}""").find(s)?.value?.toInt()

    /** 나이 칸: "5", "5살", "만 5세", "18개월", "다섯 살" → 개월 */
    fun ageMonths(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        MONTHS.find(t)?.let { return it.groupValues[1].toInt() }
        YEARS_KO.find(t)?.let { m -> KO_NUM[m.groupValues[1]]?.let { return it * 12 } }
        return num(t)?.takeIf { it in 0..110 }?.times(12)
    }

    /** 사람 입력 칸들 → 구성원. 구분(아기 · 아이 · 어른 · 어르신)은 이름 · 기타 사항의 낱말과 나이 · 키 · 몸무게로 정함 */
    fun personFrom(name: String, age: String, height: String, weight: String, note: String, id: Long = System.currentTimeMillis()): Member? {
        val n = name.trim().take(12)
        if (n.isEmpty()) return null
        val months = ageMonths(age)
        val h = num(height)?.takeIf { it in 40..210 }
        val w = num(weight)?.takeIf { it in 2..150 }
        val words = "$n $note"
        val small = (months != null && months < 24) || (h != null && h < 85) || (h == null && months == null && w != null && w < 12)
        val who = when {
            SENIOR.any { it in words } -> Who.SENIOR
            BABY.any { it in words } -> Who.BABY
            CHILD.any { it in words } -> if (small) Who.BABY else Who.CHILD
            small -> Who.BABY
            (months != null && months < 13 * 12) || (h != null && h < SeatRule.BELT_HEIGHT) ||
                (h == null && months == null && w != null && w < SeatRule.BELT_KG) -> Who.CHILD
            else -> Who.ADULT
        }
        return Member(id, n, who, months, h, w, note.trim().take(40))
    }

    /** 짐 입력 칸들 → 짐. 종류는 이름 · 기타 사항에서, 크기 칸은 "24인치" · "55x40x20" · "60L" (숫자만이면 34 이하는 인치, 넘으면 L) */
    fun luggageFrom(name: String, size: String, weight: String, note: String, id: Long = System.currentTimeMillis()): LuggageItem? {
        val n = name.trim().take(20)
        if (n.isEmpty()) return null
        val base = luggage("$n $size", id) ?: return null
        val sz = size.trim()
        val bare = if (sz.isNotEmpty() && INCH.find(sz) == null && LITERS.find(sz) == null && DIMS.find(sz) == null) num(sz) else null
        val inches = base.inches ?: bare?.takeIf { it in 14..34 }
        val liters = base.liters ?: bare?.takeIf { it in 35..400 }
        val bag = if (base.bag == Bag.SUITCASE && inches != null && inches <= 21) Bag.CARRY_ON else base.bag
        return base.copy(name = n, bag = bag, inches = inches, liters = liters, weightKg = num(weight)?.takeIf { it in 1..60 } ?: base.weightKg, note = note.trim().take(40))
    }

    fun person(text: String, id: Long = System.currentTimeMillis()): Member? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val months = MONTHS.find(t)?.groupValues?.get(1)?.toInt()
            ?: YEARS.find(t)?.groupValues?.get(1)?.toInt()?.times(12)
            ?: YEARS_KO.find(t)?.groupValues?.get(1)?.let { KO_NUM[it] }?.times(12)
        val height = (HEIGHT.find(t) ?: HEIGHT_KEY.find(t))?.groupValues?.get(1)?.toInt()?.takeIf { it in 40..210 }
        val who = when {
            SENIOR.any { it in t } -> Who.SENIOR
            BABY.any { it in t } -> Who.BABY
            CHILD.any { it in t } -> if ((months != null && months < 24) || (height != null && height < 85)) Who.BABY else Who.CHILD
            months != null || height != null -> when {
                (months != null && months < 24) || (height != null && height < 85) -> Who.BABY
                (months != null && months < 13 * 12) || (height != null && height < SeatRule.BELT_HEIGHT) -> Who.CHILD
                else -> Who.ADULT
            }
            else -> Who.ADULT
        }
        var name = t
        for (r in listOf(MONTHS, YEARS, YEARS_KO, HEIGHT, HEIGHT_KEY)) name = r.replace(name, " ")
        name = name.replace(Regex("""[,·/]"""), " ").replace(Regex("""(^|\s)(키|만)(?=\s|$)"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (name.isEmpty()) name = who.label
        return Member(id, name.take(12), who, months, height)
    }

    private val ANY_LITERS = Regex("""(\d+)\s*(L|ℓ|리터)(?![a-zA-Z])""")

    /** 적었지만 범위 밖이라 읽지 않은 리터 ("1L" · "900L"). 화면에서 "기본값으로 셌어요" 안내용 */
    fun unreadSize(text: String): String? {
        val m = ANY_LITERS.find(text) ?: return null
        val used = LITERS.find(text)?.groupValues?.get(1)?.toInt()?.takeIf { it in 5..400 }
        return if (used == null) m.value.replace(" ", "") else null
    }

    fun luggage(text: String, id: Long = System.currentTimeMillis()): LuggageItem? {
        val t = text.trim()
        if (t.isEmpty()) return null
        fun has(vararg w: String) = w.any { it in t }
        val bag = when {
            has("유모차") && has("쌍둥이", "2인용", "트윈") -> Bag.STROLLER_TWIN
            has("유모차") && has("휴대용", "트래블", "경량", "기내용", "초경량") -> Bag.STROLLER_COMPACT
            has("유모차") && has("디럭스", "양대면", "대형") -> Bag.STROLLER_DELUXE
            has("유모차") -> Bag.STROLLER
            has("기저귀", "아기 짐", "아기짐", "아기 가방", "아기가방", "분유", "육아") -> Bag.BABY_BAG
            has("아이스박스", "쿨러", "아이스 박스") -> Bag.COOLER
            has("아기침대", "아기 침대", "휴대용 침대", "범퍼침대", "바운서") -> Bag.CRIB
            has("캐리어", "여행가방", "여행 가방", "트렁크", "수트케이스", "슈트케이스") ->
                if (has("기내") || INCH.find(t)?.groupValues?.get(1)?.toInt()?.let { it <= 21 } == true) Bag.CARRY_ON else Bag.SUITCASE
            else -> Bag.OTHER
        }
        val dims = DIMS.find(t)?.groupValues?.let { (it[1].toInt() * it[2].toInt() * it[3].toInt()) / 1000 }
        val liters = LITERS.find(t)?.groupValues?.get(1)?.toInt() ?: dims
        val inches = INCH.find(t)?.groupValues?.get(1)?.toInt()?.takeIf { it in 14..34 }
        val kg = KG.find(t)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..60 }
        return LuggageItem(id, t.take(20), bag, liters?.takeIf { it in 5..400 }, inches, kg)
    }
}

/** 구성원 전원 + 짐 전부 = "우리 가족 모두" 여정 */
fun familyTrip(members: List<Member>, luggage: List<LuggageItem>): TripDraft {
    var d = TripDraft()
    for (k in members.map { it.kind } + luggage.map { it.kind }) d = TripParser.with(d, k, TripParser.count(d, k) + 1)
    return d
}

/** "우리 가족" · "평소처럼" · "가족 다" → 구성원 전원과 짐 전부 */
val FAMILY_WORDS = Regex("우리 ?가족|가족 ?(다|모두|전부)|평소처럼|평소대로|늘 타던|늘 가던|다 같이")

/** 태울 사람 · 짐 칩 하나. presetId 가 있으면 구성원 · 짐 이름으로 넣은 것 ("이봄 (아기)") */
data class DraftChip(val label: String, val person: Boolean, val slot: Slot, val presetId: Long?)

private val CHIP_ORDER = listOf(
    Slot.SENIOR to "어르신", Slot.ADULT to "어른", Slot.CHILD to "아이", Slot.INFANT to "아기",
    Slot.STROLLER to "유모차", Slot.SUITCASE to "캐리어", Slot.BABY_BAG to "아기 짐가방",
)

/**
 * 여정 → 칩. 구성원 · 짐에서 넣은 것은 이름으로 ("이봄 (아기)" · "큰 캐리어"), 말로만 넣은 나머지는 "아이 1" 처럼 수로.
 * picked 는 넣은 구성원 · 짐 id (넣은 순서).
 */
fun draftChips(d: TripDraft, picked: List<Long>, members: List<Member>, luggage: List<LuggageItem>): List<DraftChip> = buildList {
    for ((slot, generic) in CHIP_ORDER) {
        val n = TripParser.count(d, slot)
        if (n <= 0) continue
        val person = slot in listOf(Slot.SENIOR, Slot.ADULT, Slot.CHILD, Slot.INFANT)
        val named = picked.mapNotNull { id ->
            members.firstOrNull { it.id == id && it.kind == slot }?.let { DraftChip("${it.name} (${it.who.label})", true, slot, it.id) }
                ?: luggage.firstOrNull { it.id == id && it.kind == slot }?.let { DraftChip(it.name, false, slot, it.id) }
        }.take(n)
        addAll(named)
        val rest = n - named.size
        if (rest > 0) add(DraftChip(if (named.isEmpty()) "$generic $rest" else "$generic $rest 더", person, slot, null))
    }
}

/** 칸 이름 (알림 줄 · 칩) */
fun slotLabel(s: Slot) = when (s) {
    Slot.SENIOR -> "어르신"
    Slot.ADULT -> "어른"
    Slot.CHILD -> "아이"
    Slot.INFANT -> "아기 · 카시트"
    Slot.STROLLER -> "유모차"
    Slot.SUITCASE -> "캐리어 · 큰 짐"
    Slot.BABY_BAG -> "아기 짐가방"
    Slot.DEST -> ""
}

object MemberStore {
    fun encode(list: List<Member>): String = JSONArray().apply {
        for (m in list) {
            put(
                JSONObject().put("id", m.id).put("name", m.name).put("who", m.who.name)
                    .put("note", m.note)
                    .apply {
                        m.ageMonths?.let { put("ageMonths", it) }
                        m.heightCm?.let { put("heightCm", it) }
                        m.weightKg?.let { put("weightKg", it) }
                        m.hasCarSeat?.let { put("hasCarSeat", it) }
                        m.carSeatDir?.let { put("carSeatDir", it.name) }
                        m.carSeatAngle?.let { put("carSeatAngle", it) }
                        m.feedMin?.let { put("feedMin", it) }
                        if (m.mealMins.isNotEmpty()) put("mealMins", JSONArray(m.mealMins))
                    },
            )
        }
    }.toString()

    fun decode(s: String?): List<Member> = runCatching {
        val a = JSONArray(s ?: "[]")
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Member(
                o.getLong("id"), o.getString("name"),
                runCatching { Who.valueOf(o.optString("who")) }.getOrDefault(Who.ADULT),
                if (o.has("ageMonths")) o.getInt("ageMonths") else null,
                if (o.has("heightCm")) o.getInt("heightCm") else null,
                if (o.has("weightKg")) o.getInt("weightKg") else null,
                o.optString("note"),
                if (o.has("hasCarSeat")) o.getBoolean("hasCarSeat") else null,
                runCatching { CarSeatDir.valueOf(o.getString("carSeatDir")) }.getOrNull(),
                if (o.has("carSeatAngle")) o.getInt("carSeatAngle") else null,
                if (o.has("feedMin")) o.getInt("feedMin") else null,
                o.optJSONArray("mealMins")?.let { arr -> List(arr.length()) { k -> arr.getInt(k) } } ?: emptyList(),
            )
        }
    }.getOrDefault(emptyList())

    fun encodeLuggage(list: List<LuggageItem>): String = JSONArray().apply {
        for (l in list) {
            put(
                JSONObject().put("id", l.id).put("name", l.name).put("bag", l.bag.name).put("note", l.note).apply {
                    l.liters?.let { put("liters", it) }
                    l.inches?.let { put("inches", it) }
                    l.weightKg?.let { put("weightKg", it) }
                },
            )
        }
    }.toString()

    fun decodeLuggage(s: String?): List<LuggageItem> = runCatching {
        val a = JSONArray(s ?: "[]")
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            LuggageItem(
                o.getLong("id"), o.getString("name"),
                runCatching { Bag.valueOf(o.optString("bag")) }.getOrDefault(Bag.OTHER),
                if (o.has("liters")) o.getInt("liters") else null,
                if (o.has("inches")) o.getInt("inches") else null,
                if (o.has("weightKg")) o.getInt("weightKg") else null,
                o.optString("note"),
            )
        }
    }.getOrDefault(emptyList())
}
