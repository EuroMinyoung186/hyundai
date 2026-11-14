package ai.moduui.jari.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * 주행 중 아기 상태 알리기 (탭 위 "주행 중" 띠 → 아기 상태 화면).
 *
 * 버튼 하나 = 아기 상태 한 가지 + 차가 할 일 목록. 보호자가 버튼을 고르고 [보내기]를 누르면 차로 감.
 * 기본 버튼은 잠들었어요 · 깼어요 · 울어요. 보호자가 버튼을 더할 수 있음 ("똥 쌌어요" 등).
 * 차가 할 일은 폰 안 LLM 이 추천하고 (모델이 없으면 낱말 규칙), 보호자가 직접 바꿀 수 있음.
 * 할 일은 아래 목록 안에서만 고름 (운전 · 좌석은 건드리지 않음).
 */
enum class CareAction(val labelKo: String, val valueHint: String? = null, val defaultValue: String? = null) {
    NAVI_QUIET("내비 안내 소리 작게"),
    NAVI_NORMAL("내비 안내 소리 원래대로"),
    MUSIC("노래 틀기", "노래 종류 · 예: 자장가, 동요", "자장가"),
    MUSIC_OFF("음악 끄기"),
    TEMP_UP("실내 온도 1도 올리기"),
    TEMP_DOWN("실내 온도 1도 내리기"),
    NEARBY("근처 장소 추천", "장소 · 예: 기저귀 갈 곳, 수유실, 소아과", "쉬어 갈 곳"),
    ;

    /** 같이 고를 수 없는 반대 일 (소리 작게 ↔ 원래대로 등) */
    val opposite: CareAction?
        get() = when (this) {
            NAVI_QUIET -> NAVI_NORMAL
            NAVI_NORMAL -> NAVI_QUIET
            MUSIC -> MUSIC_OFF
            MUSIC_OFF -> MUSIC
            TEMP_UP -> TEMP_DOWN
            TEMP_DOWN -> TEMP_UP
            NEARBY -> null
        }

    companion object {
        fun of(id: String): CareAction? = entries.firstOrNull { it.name == id.trim().uppercase() }

        /** 같은 일 · 반대 일은 먼저 나온 것만 남기고 최대 3개 */
        fun clean(steps: List<CareStep>): List<CareStep> {
            val out = mutableListOf<CareStep>()
            for (s in steps) {
                if (out.any { it.action == s.action || it.action == s.action.opposite }) continue
                out += s
            }
            return out.take(3)
        }
    }
}

/** 차가 할 일 하나. value 는 노래 종류 · 장소처럼 값이 필요한 일만 */
data class CareStep(val action: CareAction, val value: String = "") {
    val labelKo: String get() = if (action.valueHint != null && value.isNotBlank()) "${action.labelKo}: $value" else action.labelKo
}

/** 할 일을 누가 정했는지 */
enum class CareSource(val labelKo: String) { DEFAULT("기본"), AI("AI 추천"), RULE("규칙 추천"), USER("직접 설정") }

/**
 * 아기 상태 버튼. state 는 차가 아는 아기 상태(BabyState 이름)일 때만 (기본 버튼).
 * 보호자가 더한 버튼은 state 없이 label 로 감.
 */
data class CareButton(
    val id: Long,
    val emoji: String,
    val label: String,
    val state: String? = null,
    val steps: List<CareStep> = emptyList(),
    val source: CareSource = CareSource.DEFAULT,
) {
    val custom: Boolean get() = state == null
}

object CareDefaults {
    val buttons = listOf(
        CareButton(1, "🌙", "잠들었어요", "ASLEEP", listOf(CareStep(CareAction.NAVI_QUIET), CareStep(CareAction.MUSIC_OFF))),
        CareButton(2, "👀", "깼어요", "AWAKE", listOf(CareStep(CareAction.NAVI_NORMAL))),
        CareButton(3, "😢", "울어요", "CRYING", listOf(CareStep(CareAction.MUSIC, "자장가"))),
    )

    /** 버튼을 더할 때 고르는 그림 */
    val emojis = listOf("💩", "💧", "🤮", "🤒", "🥵", "🥶", "🍼", "😪", "🧸", "❗")
}

/** LLM 이 없거나 답을 못 알아들었을 때 쓰는 낱말 규칙 */
object CareRules {
    fun suggest(label: String): List<CareStep> {
        val t = label.replace(" ", "")
        val out = mutableListOf<CareStep>()
        fun has(vararg w: String) = w.any { it in t }
        if (has("똥", "응가", "기저귀", "쉬했", "오줌", "쌌")) out += CareStep(CareAction.NEARBY, "기저귀 갈 곳")
        if (has("배고", "수유", "분유", "젖", "맘마")) out += CareStep(CareAction.NEARBY, "수유실")
        if (has("토했", "토해", "열나", "열이", "아파", "아픈", "기침")) out += CareStep(CareAction.NEARBY, "소아과")
        if (has("더워", "더운", "땀")) out += CareStep(CareAction.TEMP_DOWN)
        if (has("추워", "추운", "떨어", "떨고")) out += CareStep(CareAction.TEMP_UP)
        // "잠에서 깼어요" 는 깸으로 보도록 깸을 먼저 봄 (반대 일은 먼저 나온 것만 남음)
        if (has("깼", "깨어", "일어났")) out += CareStep(CareAction.NAVI_NORMAL)
        if (has("울어", "울음", "보채", "칭얼", "짜증")) out += CareStep(CareAction.MUSIC, "자장가")
        if (has("잠", "자요", "잤", "졸려", "졸림")) out += listOf(CareStep(CareAction.NAVI_QUIET), CareStep(CareAction.MUSIC_OFF))
        if (has("심심", "놀아")) out += CareStep(CareAction.MUSIC, "동요")
        return CareAction.clean(out)
    }
}

/** 폰 안 LLM 에게 버튼 이름으로 할 일을 추천받기 */
object CarePrompt {
    val SYSTEM = buildString {
        append("너는 차 안 아기 돌봄 도우미다. 보호자가 아기 상태를 알리면, 차가 할 일을 아래 목록에서 1~3개 고른다.\n")
        append("목록:\n")
        append("NAVI_QUIET = 내비 안내 소리 작게\n")
        append("NAVI_NORMAL = 내비 안내 소리 원래대로\n")
        append("MUSIC = 노래 틀기 (값: 노래 종류)\n")
        append("MUSIC_OFF = 음악 끄기\n")
        append("TEMP_UP = 실내 온도 1도 올리기\n")
        append("TEMP_DOWN = 실내 온도 1도 내리기\n")
        append("NEARBY = 근처 장소 추천 (값: 장소 종류)\n")
        append("답은 한 줄에 하나씩 ID 또는 ID: 값 으로만 쓴다. 설명은 쓰지 않는다.\n")
        append("예)\n")
        append("아기 상태: 잠들었어요\nNAVI_QUIET\nMUSIC_OFF\n")
        append("아기 상태: 울어요\nMUSIC: 자장가\n")
        append("아기 상태: 땀을 흘려요\nTEMP_DOWN\n")
        append("아기 상태: 기저귀가 젖었어요\nNEARBY: 기저귀 갈 곳\n")
    }

    fun build(label: String) = "아기 상태: ${label.trim()}"

    private val LINE = Regex("""^\s*[-*\d.)\s]*([A-Za-z_]+)\s*(?:[:=]\s*(.+?))?\s*$""")

    /** LLM 답 → 할 일 (목록에 없는 것은 버림, 최대 3개) */
    fun parse(answer: String): List<CareStep> = CareAction.clean(
        answer
            .replace(Regex("""<think>[\s\S]*?</think>"""), "")
            .lines()
            .mapNotNull { line ->
                val m = LINE.find(line) ?: return@mapNotNull null
                val a = CareAction.of(m.groupValues[1]) ?: return@mapNotNull null
                val v = m.groupValues[2].trim().take(20)
                CareStep(a, if (a.valueHint != null) v.ifBlank { a.defaultValue.orEmpty() } else "")
            },
    )
}

/** 차로 보내는 메시지 (type "baby" 에 키를 더함. 차가 모르는 키는 무시됨) */
object CareMessage {
    fun build(b: CareButton): JSONObject = JSONObject()
        .put("type", "baby")
        .apply { b.state?.let { put("state", it) } }
        .put("label", b.label)
        .put("emoji", b.emoji)
        .put("custom", b.custom)
        .put(
            "actions",
            JSONArray(
                b.steps.map { s ->
                    JSONObject().put("do", s.action.name).apply { if (s.value.isNotBlank()) put("value", s.value) }
                },
            ),
        )
}

object CareStore {
    fun encode(list: List<CareButton>): String = JSONArray(
        list.map { b ->
            JSONObject()
                .put("id", b.id).put("emoji", b.emoji).put("label", b.label)
                .apply { b.state?.let { put("state", it) } }
                .put("source", b.source.name)
                .put("steps", JSONArray(b.steps.map { JSONObject().put("do", it.action.name).put("value", it.value) }))
        },
    ).toString()

    /** 저장된 게 없으면 기본 버튼 */
    fun decode(s: String?): List<CareButton> {
        if (s.isNullOrBlank()) return CareDefaults.buttons
        return runCatching {
            val a = JSONArray(s)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                val st = o.optJSONArray("steps") ?: JSONArray()
                CareButton(
                    id = o.getLong("id"),
                    emoji = o.optString("emoji", "❗"),
                    label = o.getString("label"),
                    state = o.optString("state").ifBlank { null },
                    steps = List(st.length()) { j -> st.getJSONObject(j) }.mapNotNull { so ->
                        CareAction.of(so.optString("do"))?.let { CareStep(it, so.optString("value")) }
                    },
                    source = runCatching { CareSource.valueOf(o.optString("source")) }.getOrDefault(CareSource.DEFAULT),
                )
            }
        }.getOrDefault(CareDefaults.buttons)
    }
}
