package ai.moduui.jari.phone

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 좌석 기억 (히스토리 탭). 같은 여정을 또 갈 일은 적어서, 여정 대신 "누가 어느 자리에 어떻게 앉았는지" 를 남김.
 * 예: 2열 오른쪽 · 이봄 · 후방 카시트 40° · 앞뒤 0% · 등받이 25°.
 *
 * @param seat R2_RIGHT 같은 좌석 id
 * @param who 앉은 사람 (구성원 이름 · "아이" 같은 이름 없는 사람). 모르면 빈 값
 * @param detail 카시트 등 ("후방 카시트 40°" · "부스터 시트")
 * @param foreAft · backrest 그때 좌석 위치 0..100 (차가 알려 준 값 · 직접 맞춘 값). 모르면 null
 * @param source "출발 준비" · "직접 조절"
 */
data class SeatRecord(
    val at: Long,
    val seat: String,
    val who: String,
    val detail: String,
    val foreAft: Int?,
    val backrest: Int?,
    val source: String,
)

/** 같은 자리 · 같은 사람 · 같은 설정을 묶은 것 ("3번 · 마지막 11월 13일") */
data class SeatHabit(val seat: String, val who: String, val detail: String, val times: Int, val last: SeatRecord)

/** 등받이 각도 범위 (세로에서 뒤로 기운 각도). 승용차 등받이는 보통 20~25° 로 앉고, 끝까지 세우면 15° 안팎, 끝까지 눕히면 50° 안팎 */
const val BACKREST_MIN_DEG = 15
const val BACKREST_MAX_DEG = 50

/** 등받이 위치(0..100, 100 = 가장 세움) → 각도(°, 15 ~ 50). 3D 도 같은 환산 */
fun backrestDeg(b: Int): Int = (BACKREST_MAX_DEG - b.coerceIn(0, 100) * (BACKREST_MAX_DEG - BACKREST_MIN_DEG) / 100f).roundToInt()

fun degToBackrest(deg: Int): Int = ((BACKREST_MAX_DEG - deg) * 100f / (BACKREST_MAX_DEG - BACKREST_MIN_DEG)).roundToInt().coerceIn(0, 100)

/** 좌석 위치 한 줄: "앞뒤 30% · 등받이 25°" */
fun positionText(foreAft: Int?, backrest: Int?): String = listOfNotNull(
    foreAft?.let { "앞뒤 $it%" },
    backrest?.let { "등받이 ${backrestDeg(it)}°" },
).joinToString(" · ")

private val SEAT_ORDER = listOf("R1_LEFT", "R1_RIGHT", "R2_LEFT", "R2_CENTER", "R2_RIGHT", "R3_LEFT", "R3_CENTER", "R3_RIGHT")

/** 좌석별로 묶기 (좌석 순서대로, 안에서는 최근 것 먼저) */
fun seatHabits(records: List<SeatRecord>): List<Pair<String, List<SeatHabit>>> =
    records.groupBy { it.seat }
        .toList()
        .sortedBy { (seat, _) -> SEAT_ORDER.indexOf(seat).let { if (it < 0) 99 else it } }
        .map { (seat, list) ->
            seat to list.groupBy { it.who to it.detail }
                .map { (key, l) -> SeatHabit(seat, key.first, key.second, l.size, l.maxBy { it.at }) }
                .sortedByDescending { it.last.at }
        }

/** 이 사람이 마지막으로 이 자리에 앉았을 때 (직접 맞추기에서 "지난번대로") */
fun lastAt(records: List<SeatRecord>, seat: String, who: String?): SeatRecord? =
    records.filter { it.seat == seat && (who == null || it.who == who) && (it.foreAft != null || it.backrest != null) }.maxByOrNull { it.at }

object SeatMemoryStore {
    fun encode(list: List<SeatRecord>): String = JSONArray().apply {
        for (r in list) {
            put(
                JSONObject().put("at", r.at).put("seat", r.seat).put("who", r.who).put("detail", r.detail).put("source", r.source).apply {
                    r.foreAft?.let { put("foreAft", it) }
                    r.backrest?.let { put("backrest", it) }
                },
            )
        }
    }.toString()

    fun decode(s: String?): List<SeatRecord> = runCatching {
        val a = JSONArray(s ?: "[]")
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            SeatRecord(
                o.getLong("at"), o.getString("seat"), o.optString("who"), o.optString("detail"),
                if (o.has("foreAft")) o.getInt("foreAft") else null,
                if (o.has("backrest")) o.getInt("backrest") else null,
                o.optString("source"),
            )
        }
    }.getOrDefault(emptyList())
}
