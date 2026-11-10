package ai.moduui.core.gleo

import ai.moduui.core.model.BabyState
import ai.moduui.core.model.LuggageType
import ai.moduui.core.model.TripRequest
import ai.moduui.core.util.MiniJson
import ai.moduui.core.util.intOrNull
import ai.moduui.core.util.stringOrNull

/**
 * Gleo Interface 계약 (Pleos "Implementing the Gleo Interface" 가이드 기준).
 *
 * - 앱은 assets/schema.json 으로 호출 가능한 함수를 등록함 (최대 3개, 이름은 대문자_밑줄, 설명은 영어).
 * - Gleo AI(LLM)가 사용자 발화를 함수 호출로 바꿔 Intent로 보냄: action = 함수 이름, extra "ACTION_PARAM" = JSON 문자열.
 *   component "activity" 는 startActivity, "service" 는 sendBroadcast 로 전달됨.
 * - 앱은 7초 안에 broadcast "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE" 로 결과를 돌려줌
 *   (extra "packageName", "response" = 콜백 JSON).
 *
 * 그래서 앱 안에 LLM을 넣지 않음. 자연어 이해는 Gleo가 하고, 앱은 구조화된 파라미터만 받음.
 * (Pleos Connect Emulator 2.1.0 릴리스 노트: LLM SDK 지원 종료)
 */
enum class GleoComponent(val json: String) { ACTIVITY("activity"), SERVICE("service") }

enum class GleoParamType(val json: String) { STRING("string"), NUMBER("number"), BOOLEAN("boolean"), ARRAY("array") }

data class GleoParam(
    val name: String,
    val type: GleoParamType,
    val description: String,
    val enumValues: List<String>? = null,
)

data class GleoFunction(
    val name: String,
    val description: String,
    val component: GleoComponent,
    val params: List<GleoParam>,
    val required: List<String> = emptyList(),
)

enum class GleoStatus(val json: String) { SUCCESS("success"), ASKING("asking"), FAIL("fail") }

enum class GleoMode(val json: String) { VOICE("voice"), BEEP("beep"), SILENT("silent") }

/** 콜백. message 는 영어 한 문장 (장소 이름 등은 한국어 허용). */
data class GleoCallback(
    val functionName: String,
    val status: GleoStatus,
    val message: String,
    val mode: GleoMode = GleoMode.VOICE,
    val data: List<Map<String, Any?>>? = null,
) {
    fun toJson(): String {
        val map = linkedMapOf<String, Any?>(
            "functionName" to functionName,
            "status" to status.json,
            "message" to message,
            "mode" to mode.json,
        )
        // 가이드: data 는 "key-value 객체 배열을 담은 JSON 문자열"
        if (data != null) map["data"] = MiniJson.stringify(data)
        return MiniJson.stringify(map)
    }
}

sealed interface GleoCommand {
    val functionName: String

    data class PrepareDeparture(val trip: TripRequest) : GleoCommand {
        override val functionName get() = GleoContract.PREPARE_DEPARTURE.name
    }

    data class ReportBabyState(val state: BabyState?, val fed: Boolean) : GleoCommand {
        override val functionName get() = GleoContract.REPORT_BABY_STATE.name
    }

    data class AddNursingStop(val choice: Int?) : GleoCommand {
        override val functionName get() = GleoContract.ADD_NURSING_STOP.name
    }

    data class Invalid(override val functionName: String, val reason: String) : GleoCommand
}

object GleoContract {
    const val ACTION_PARAM_EXTRA = "ACTION_PARAM"
    const val CALLBACK_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    const val CALLBACK_EXTRA_PACKAGE = "packageName"
    const val CALLBACK_EXTRA_RESPONSE = "response"

    /** Gleo는 7초가 지나면 타임아웃 처리함. 여유를 두고 5초 안에 응답. */
    const val RESPONSE_BUDGET_MS = 5_000L
    const val GLEO_TIMEOUT_MS = 7_000L
    const val MAX_FUNCTIONS = 3

    val PREPARE_DEPARTURE = GleoFunction(
        name = "PREPARE_DEPARTURE",
        description = "Plan car seat placement, seat positions and cargo space for the next trip with a baby, then show the plan for confirmation.",
        component = GleoComponent.ACTIVITY,
        params = listOf(
            GleoParam("destination", GleoParamType.STRING, "Name of the trip destination."),
            GleoParam("seniors", GleoParamType.NUMBER, "Number of elderly passengers, not counting the driver."),
            GleoParam("adults", GleoParamType.NUMBER, "Number of other adult passengers, not counting the driver or elderly passengers."),
            GleoParam("children", GleoParamType.NUMBER, "Number of children who sit in a booster seat."),
            GleoParam("infants", GleoParamType.NUMBER, "Number of babies who ride in a car seat."),
            GleoParam("strollers", GleoParamType.NUMBER, "Number of strollers to load."),
            GleoParam("suitcases", GleoParamType.NUMBER, "Number of suitcases to load."),
            GleoParam("babyBags", GleoParamType.NUMBER, "Number of baby bags or diaper bags to load."),
            GleoParam("portableCribs", GleoParamType.NUMBER, "Number of portable cribs to load."),
            GleoParam("otherBags", GleoParamType.NUMBER, "Number of other bags or boxes to load."),
        ),
    )

    val REPORT_BABY_STATE = GleoFunction(
        name = "REPORT_BABY_STATE",
        description = "Record the current state of the baby in the rear car seat so the car can adjust guidance and stops.",
        component = GleoComponent.SERVICE,
        params = listOf(
            GleoParam(
                "state", GleoParamType.STRING, "Current state of the baby.",
                enumValues = listOf("asleep", "awake", "crying", "hungry", "fed"),
            ),
        ),
        required = listOf("state"),
    )

    val ADD_NURSING_STOP = GleoFunction(
        name = "ADD_NURSING_STOP",
        description = "Find a highway rest area with a nursing room along the current route and add it as a waypoint.",
        component = GleoComponent.SERVICE,
        params = listOf(
            GleoParam("choice", GleoParamType.NUMBER, "Position of the suggested rest area to add, starting from one. Omit it to add the best match."),
        ),
    )

    val functions: List<GleoFunction> = listOf(PREPARE_DEPARTURE, REPORT_BABY_STATE, ADD_NURSING_STOP)

    fun function(name: String): GleoFunction? = functions.firstOrNull { it.name == name }

    /** assets/schema.json 내용. */
    fun schema(packageName: String, version: String, invokeWord: String = "모두의 자리"): Map<String, Any?> = linkedMapOf(
        "packageName" to packageName,
        "appDescription" to "A family car app that prepares seats for a baby car seat and watches over the baby during the trip.",
        // invokeWord 는 가이드 예시에만 있고 필드 정의는 없음 (예시 값이 한국어 앱 이름). 같은 방식으로 앱 이름을 넣음.
        "invokeWord" to invokeWord,
        "version" to version,
        "actionCalls" to functions.map { f ->
            linkedMapOf(
                "type" to "function",
                "function" to linkedMapOf(
                    "component" to f.component.json,
                    "name" to f.name,
                    "description" to f.description,
                    "parameters" to linkedMapOf(
                        "type" to "object",
                        "properties" to linkedMapOf<String, Any?>().apply {
                            for (p in f.params) {
                                put(p.name, linkedMapOf<String, Any?>("type" to p.type.json, "description" to p.description).apply {
                                    if (p.enumValues != null) put("enum", p.enumValues)
                                })
                            }
                        },
                        "required" to f.required,
                    ),
                ),
            )
        },
    )

    fun schemaJson(packageName: String, version: String): String = MiniJson.stringify(schema(packageName, version), "  ")

    /** 가이드 규칙 검사. 빈 목록이면 통과. */
    fun validate(fns: List<GleoFunction> = functions, appDescription: String? = null): List<String> {
        val errors = mutableListOf<String>()
        if (fns.size > MAX_FUNCTIONS) errors += "actionCalls must be at most $MAX_FUNCTIONS (was ${fns.size})"
        if (fns.map { it.name }.toSet().size != fns.size) errors += "function names must be unique"
        val nameRule = Regex("^[A-Z]+(_[A-Z]+){1,2}$") // 동사 + 대상, 2~3 단어
        for (f in fns) {
            if (!nameRule.matches(f.name)) errors += "${f.name}: name must be 2-3 UPPERCASE words joined by '_'"
            errors += checkEnglish("${f.name}.description", f.description)
            for (p in f.params) {
                errors += checkEnglish("${f.name}.${p.name}.description", p.description)
                if (p.enumValues != null && p.type != GleoParamType.STRING) errors += "${f.name}.${p.name}: enum only for string"
            }
            val names = f.params.map { it.name }
            f.required.filterNot { it in names }.forEach { errors += "${f.name}: required '$it' is not a property" }
            if (f.required != names.filter { it in f.required }) errors += "${f.name}: required must follow property order"
        }
        if (appDescription != null) errors += checkEnglish("appDescription", appDescription)
        return errors
    }

    private fun checkEnglish(where: String, text: String): List<String> {
        val errs = mutableListOf<String>()
        if (text.isBlank()) errs += "$where: empty"
        if (text.any { it.code > 0x7E }) errs += "$where: must be English (ASCII)"
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).count { it.isNotBlank() }
        if (sentences > 2) errs += "$where: at most two sentences"
        if (Regex("(?i)e\\.g\\.|for example|such as").containsMatchIn(text)) errs += "$where: avoid concrete examples"
        return errs
    }

    /** Intent action + ACTION_PARAM 을 앱 명령으로 바꿈. */
    fun toCommand(action: String?, actionParamJson: String?): GleoCommand {
        val fn = action?.let(::function) ?: return GleoCommand.Invalid(action ?: "", "unknown action")
        val p = try {
            MiniJson.parseObject(actionParamJson)
        } catch (e: IllegalArgumentException) {
            return GleoCommand.Invalid(fn.name, "invalid ACTION_PARAM: ${e.message}")
        }
        return when (fn) {
            PREPARE_DEPARTURE -> {
                fun count(key: String): Int = (p.intOrNull(key) ?: 0).coerceIn(0, 9)
                val luggage = buildMap {
                    put(LuggageType.STROLLER, count("strollers"))
                    put(LuggageType.SUITCASE, count("suitcases"))
                    put(LuggageType.BABY_BAG, count("babyBags"))
                    put(LuggageType.PORTABLE_CRIB, count("portableCribs"))
                    put(LuggageType.OTHER, count("otherBags"))
                }.filterValues { it > 0 }
                GleoCommand.PrepareDeparture(
                    TripRequest(
                        destinationName = p.stringOrNull("destination"),
                        seniors = count("seniors"),
                        adults = count("adults"),
                        children = count("children"),
                        // 모두의 자리는 아기 동반 여정이 전제. 말하지 않으면 1명으로 봄.
                        infants = p.intOrNull("infants")?.coerceIn(0, 4) ?: 1,
                        luggage = luggage,
                    ),
                )
            }
            REPORT_BABY_STATE -> when (p.stringOrNull("state")?.lowercase()) {
                "asleep" -> GleoCommand.ReportBabyState(BabyState.ASLEEP, fed = false)
                "awake" -> GleoCommand.ReportBabyState(BabyState.AWAKE, fed = false)
                "crying" -> GleoCommand.ReportBabyState(BabyState.CRYING, fed = false)
                "hungry" -> GleoCommand.ReportBabyState(BabyState.HUNGRY, fed = false)
                "fed" -> GleoCommand.ReportBabyState(null, fed = true)
                null -> GleoCommand.Invalid(fn.name, "missing state")
                else -> GleoCommand.Invalid(fn.name, "unsupported state")
            }
            ADD_NURSING_STOP -> GleoCommand.AddNursingStop(p.intOrNull("choice")?.takeIf { it in 1..5 })
            else -> GleoCommand.Invalid(fn.name, "not handled")
        }
    }

    /** 파라미터가 빠졌을 때 되물을 문장 (status=asking) */
    fun askingFor(command: GleoCommand.Invalid): GleoCallback =
        GleoCallback(command.functionName, GleoStatus.ASKING, when (command.functionName) {
            REPORT_BABY_STATE.name -> "Is the baby asleep, awake, crying, or hungry?"
            else -> "Please say that again with a little more detail."
        })
}
