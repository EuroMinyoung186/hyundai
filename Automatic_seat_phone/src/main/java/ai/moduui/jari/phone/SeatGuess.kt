package ai.moduui.jari.phone

/**
 * 좌석 자세 예측 (등받이 각도 · 앞뒤). 배치가 정해지면 좌석마다 "이렇게 맞추면 좋겠다" 를 냄.
 * 순서: 1) 이 사람이 이 자리에 앉았던 기억  2) 이 사람의 평소 등받이  3) 규칙 (카시트 · 어르신 · 멀미 · 잠).
 * 숫자를 내는 일이라 작은 LLM 대신 기억과 규칙으로 정함 (같은 입력 → 같은 결과).
 *
 * @param foreAft 0..100 (100 = 맨 앞). 바꿀 필요 없으면 null
 * @param backrest 0..100 (100 = 가장 세움). 각도는 backrestDeg
 * @param why 화면에 보이는 이유 ("평소 3번" · "카시트 고정: 세워서")
 */
data class SeatGuess(val foreAft: Int?, val backrest: Int, val why: String) {
    val text: String get() = positionText(foreAft, backrest) + " · " + why
}

object SeatGuessRules {
    /** 규칙 기본 각도 (°) */
    const val CHILD_SEAT_DEG = 20
    const val SENIOR_DEG = 22
    const val CARSICK_DEG = 20
    const val NORMAL_DEG = 24
    const val SLEEP_DEG = 32

    /** 앞좌석 바로 뒤에 후방 카시트가 있으면 앞좌석을 이만큼 앞으로 (카시트 공간) */
    const val ROOM_FOR_REAR_SEAT = 70

    /** 어르신이 1열이면 다리 공간을 위해 조금 뒤로 */
    const val SENIOR_FRONT_FORE = 35

    fun guess(plan: Map<String, String>, riders: List<Rider>, memory: List<SeatRecord>): Map<String, SeatGuess> {
        val byName = riders.associateBy { it.label }
        val out = linkedMapOf<String, SeatGuess>()
        for ((seat, name) in plan) {
            val r = byName[name] ?: continue
            if (r.driver) continue // 운전자는 직접 맞춤
            // 1) 이 자리에 앉았던 기억
            val here = memory.filter { it.seat == seat && it.who == name && it.backrest != null }
            if (here.isNotEmpty()) {
                val last = here.maxBy { it.at }
                out[seat] = SeatGuess(last.foreAft, last.backrest!!, if (here.size > 1) "평소 ${here.size}번" else "지난번 그대로")
                continue
            }
            // 2) 다른 자리에서의 평소 등받이
            val any = memory.filter { it.who == name && it.backrest != null }.maxByOrNull { it.at }
            if (any != null) {
                out[seat] = SeatGuess(null, any.backrest!!, "평소 자세 (${seatName(any.seat)})")
                continue
            }
            // 3) 규칙
            val note = r.note
            val (deg, why) = when {
                r.needsChildSeat -> CHILD_SEAT_DEG to "카시트는 등받이를 세워야 단단히 고정돼요"
                "멀미" in note -> CARSICK_DEG to "멀미: 세워 앉으면 덜해요"
                listOf("잠", "졸", "낮잠").any { it in note } -> SLEEP_DEG to "잘 때: 조금 눕혀요"
                r.who == Who.SENIOR -> SENIOR_DEG to "어르신: 조금 세워 타고 내리기 쉽게"
                else -> NORMAL_DEG to "보통 자세"
            }
            val fore = if (r.who == Who.SENIOR && seatRow(seat) == 1) SENIOR_FRONT_FORE else null
            out[seat] = SeatGuess(fore, degToBackrest(deg), why)
        }
        // 후방 카시트 바로 앞 좌석(운전석 빼고)은 앞으로 당겨 카시트 공간
        for ((seat, name) in plan) {
            val r = byName[name] ?: continue
            if (r.need != SeatNeed.REAR_SEAT || seatRow(seat) != 2) continue
            val front = "R1_" + seat.substringAfter('_')
            if (front == "R1_LEFT" || front !in plan) continue
            val g = out[front] ?: continue
            if ((g.foreAft ?: 0) < ROOM_FOR_REAR_SEAT) {
                out[front] = g.copy(foreAft = ROOM_FOR_REAR_SEAT, why = g.why + " · 뒤 후방 카시트 공간")
            }
        }
        return out
    }
}
