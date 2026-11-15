package ai.moduui.jari.navi

/**
 * 앞길 안내 한 줄. urgent 면 주의 색 (아기가 자는데 덜컹거릴 곳이 가까움).
 * key 는 같은 지점을 두 번 말하지 않으려고 씀 (거리는 빼고 종류만), voice 는 음성으로 읽을 문장.
 */
data class RoadAdvice(val text: String, val urgent: Boolean, val key: String = text, val voice: String = text)

/**
 * 내비 알림(회전 · 주의 지점)을 아기 관점으로 바꿈. 규칙 기반 (LLM 아님).
 * - 과속방지턱 · 철길 · 파손 도로 같은 덜컹거릴 곳이 가까우면: 아기가 자면 "천천히 넘어 주세요"
 * - 급회전 · 유턴 · 회전교차로가 가까우면: 아기가 잘 때만 "부드럽게"
 * - 어린이 보호구역: 늘
 * 앱은 차 속도를 바꾸지 않음. 운전자에게 짧게 알리기만 함.
 */
object RoadAhead {
    /** 이 거리 안에 들어오면 알림 (m) */
    const val NEAR_M = 500
    const val TURN_NEAR_M = 300

    private val BUMPY = setOf(
        "SPEED_BUMP", "RAILROAD_CROSSING", "ROAD_DAMAGE_ALERT", "DOWNHILL_CAUTION_AREA",
        "NARROW_ROAD", "HIGHWAY_CONSTRUCTION", "SLIPPERY_ROAD_ALERT", "FALLING_ROCK_AREA",
    )

    fun isSharpTurn(type: String) =
        type == "TURN_SHARP_R" || type == "TURN_SHARP_L" || type == "TURN_UTURN" || type.startsWith("TURN_ENTER_ROUNDABOUT")

    /** 아기 관점에서 알릴 만한 주의 지점 이름. 단속 안내류는 null (내비가 이미 알려 줌) */
    fun cautionKo(type: String): String? = when (type) {
        "SPEED_BUMP" -> "과속방지턱"
        "SCHOOL_ZONE" -> "어린이 보호구역"
        "RAILROAD_CROSSING" -> "철길 건널목"
        "ROAD_DAMAGE_ALERT" -> "파손된 길"
        "DOWNHILL_CAUTION_AREA" -> "급한 내리막"
        "NARROW_ROAD" -> "좁은 길"
        "HIGHWAY_CONSTRUCTION" -> "공사 구간"
        "SLIPPERY_ROAD_ALERT" -> "미끄러운 길"
        "FALLING_ROCK_AREA" -> "낙석 주의 구간"
        "ACCIDENT_PRONE_AREA" -> "사고 잦은 곳"
        "FOG_AREA" -> "안개 잦은 곳"
        "WILDLIFE_CROSSING" -> "야생동물 출몰 구간"
        "WRONG_WAY_DRIVING_ALERT" -> "역주행 차 주의"
        else -> null
    }

    fun turnKo(type: String): String = when {
        type == "TURN_STRAIGHT" -> "직진"
        type == "TURN_LEFT" -> "좌회전"
        type == "TURN_RIGHT" -> "우회전"
        type == "TURN_KEEP_L" -> "왼쪽 길"
        type == "TURN_KEEP_R" -> "오른쪽 길"
        type == "TURN_SHARP_L" -> "급좌회전"
        type == "TURN_SHARP_R" -> "급우회전"
        type == "TURN_UTURN" -> "유턴"
        type.startsWith("TURN_ENTER_IC") -> "나들목 진입"
        type.startsWith("TURN_EXIT_IC") -> "나들목 나감"
        type.startsWith("TURN_ENTER_UNDER") -> "지하차도"
        type.startsWith("TURN_AVOID_UNDER") -> "지하차도 옆길"
        type.startsWith("TURN_ENTER_OVER") -> "고가차도"
        type.startsWith("TURN_AVOID_OVER") -> "고가차도 옆길"
        type.startsWith("TURN_ENTER_ROUNDABOUT") -> "회전교차로"
        type == "TURN_EXIT_ROUNDABOUT" -> "회전교차로 나감"
        type == "TURN_WAY" -> "경유지"
        type == "TURN_ARRIVE" -> "도착"
        type == "TURN_TUNNEL" -> "터널"
        type == "TURN_DEVIDE_MERGE" -> "갈림길"
        type == "TURN_TOLL" -> "요금소"
        type == "TURN_ENTER_SERVICEAREA" || type == "TURN_RESTAREA" -> "휴게소"
        else -> "안내"
    }

    /** 회전 화살표 글자 (아이콘 대신, 큰 글씨로) */
    fun turnArrow(type: String): String = when {
        type == "TURN_UTURN" -> "↶"
        type == "TURN_LEFT" || type == "TURN_SHARP_L" -> "↰"
        type == "TURN_RIGHT" || type == "TURN_SHARP_R" -> "↱"
        type.endsWith("_L") -> "↖"
        type.endsWith("_R") -> "↗"
        type.startsWith("TURN_ENTER_ROUNDABOUT") || type == "TURN_EXIT_ROUNDABOUT" -> "⟳"
        type == "TURN_ARRIVE" || type == "TURN_WAY" -> "◎"
        type == "TURN_ENTER_SERVICEAREA" || type == "TURN_RESTAREA" -> "P"
        else -> "↑"
    }

    /** "유턴" → "유턴이에요", "회전교차로" → "회전교차로예요" */
    fun copulaKo(word: String): String {
        val c = word.lastOrNull() ?: return word
        val batchim = c in '가'..'힣' && (c - '가') % 28 != 0
        return if (batchim) "${word}이에요" else "${word}예요"
    }

    /** "지민" → "지민이가", "아기" → "아기가" (받침에 따라) */
    fun subjectKo(name: String): String {
        val c = name.lastOrNull() ?: return name
        val batchim = c in '가'..'힣' && (c - '가') % 28 != 0
        return if (batchim) "${name}이가" else "${name}가"
    }

    fun distanceKo(m: Int): String = if (m >= 1000) "%.1fkm".format(m / 1000f) else "${m.coerceAtLeast(0)}m"

    fun durationKo(seconds: Int): String {
        val min = (seconds + 59) / 60
        return if (min >= 60) "${min / 60}시간 ${min % 60}분" else "${min}분"
    }

    /** 지금 운전자에게 알릴 한 줄. 없으면 null */
    fun advice(navi: NaviState, asleep: Boolean, nickname: String): RoadAdvice? {
        val name = subjectKo(nickname.ifBlank { "아기" })
        navi.cautions.firstOrNull { it.type in BUMPY && it.meters in 0..NEAR_M }?.let { c ->
            val what = cautionKo(c.type) ?: return@let
            return if (asleep) RoadAdvice(
                "${distanceKo(c.meters)} 앞 $what · $name 자요, 천천히 넘어 주세요", urgent = true,
                key = c.type, voice = "앞에 ${what}이 있어요. $name 자니까 천천히 넘어 주세요",
            )
            else RoadAdvice("${distanceKo(c.meters)} 앞 $what · 천천히 가요", urgent = false, key = c.type)
        }
        if (asleep) {
            navi.nextTurn?.takeIf { isSharpTurn(it.type) && it.meters in 0..TURN_NEAR_M }?.let { t ->
                return RoadAdvice(
                    "${distanceKo(t.meters)} 앞 ${turnKo(t.type)} · $name 자요, 부드럽게 돌아 주세요", urgent = true,
                    key = t.type, voice = "곧 ${copulaKo(turnKo(t.type))}. $name 자니까 부드럽게 돌아 주세요",
                )
            }
        }
        navi.cautions.firstOrNull { it.type == "SCHOOL_ZONE" && it.meters in 0..NEAR_M }?.let { c ->
            return RoadAdvice("${distanceKo(c.meters)} 앞 어린이 보호구역 · 30km/h", urgent = false, key = c.type)
        }
        return null
    }

    /** 다음 경유지(수유 휴게소 등). 없으면 null */
    fun nextWaypoint(navi: NaviState): NaviLeg? = navi.legs.firstOrNull { it.waypoint && it.meters > 0 }

    /** 목적지까지 (onDrivingInfo). 없으면 null */
    fun destinationLeg(navi: NaviState): NaviLeg? = navi.legs.lastOrNull { !it.waypoint }
}
