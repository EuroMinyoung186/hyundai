package ai.moduui.jari.phone

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** P-1 시작 → P-2 코드 입력 → P-2 연결됨 · 기능 동의 → 홈 */
enum class Stage { START, BABY, CONNECT, LINKED, MAIN }
/** 아래 탭: 홈 · 3D 차량 시뮬 · 히스토리 · 설정 */
enum class Tab { HOME, MEMBERS, SIM, DRIVE, HISTORY, SETTINGS }

/** 여정 입력의 알림 한 줄: "아이 1명 추가" (말한 문장과 함께. 버튼으로 바꾸면 heard 는 빈 값) */
data class ChangeLine(val id: Long, val text: String, val heard: String, val added: Boolean)

/** AI 좌석 추천 진행 (3D 시뮬 탭) */
enum class AiStage { IDLE, DOWNLOADING, LOADING, THINKING, DONE }

/**
 * plans: 이번에 나온 안 전부 (AI 안 · 다시 시킨 안 · 규칙 안). chosen: 쓰는 안 (규칙 통과한 AI 안, 없으면 규칙 안).
 * note: 한 줄 설명 (모델 없음 · 실패 이유 등)
 */
data class AiUi(
    val stage: AiStage = AiStage.IDLE,
    val progress: Int = 0,
    val plans: List<CheckedPlan> = emptyList(),
    val chosen: CheckedPlan? = null,
    val note: String? = null,
    val seconds: Int? = null,
    /** 걸린 시간 나눠 보기: 모델 준비 · AI 생각 (초) */
    val loadSec: Int? = null,
    val thinkSec: Int? = null,
    /** 추천에 참고한 기억 ("비슷한 여정 87% · 11월 12일 시댁", "이봄 평소 2열 오른쪽 5번") */
    val memory: List<String> = emptyList(),
    /** 고른 안의 좌석마다 예상 자세 (등받이 각도 · 앞뒤) */
    val guess: Map<String, SeatGuess> = emptyMap(),
    /** AI 자세에서 규칙이 고친 것 ("이봄은 카시트라 20°로 세움") */
    val fixes: List<String> = emptyList(),
)

/** 히스토리: 차로 보낸 여정 한 건 */
data class TripLog(val at: Long, val trip: TripDraft, val seats: Map<String, String> = emptyMap())

data class PhoneUi(
    val stage: Stage = Stage.START,
    val tab: Tab = Tab.HOME,
    /** 홈에서 [고치기]로 연 여정 입력 화면 */
    val editing: Boolean = false,
    /** P-2 연결됨 화면의 동의 체크 */
    val agreed: Boolean = false,
    val codeInput: String = "",
    val connecting: Boolean = false,
    val connectError: String? = null,
    val code: String? = null,
    val relayReachable: Boolean = true,
    val carOnline: Boolean = false,
    val status: CarStatus? = null,
    val draft: TripDraft = TripDraft(),
    val heard: Set<Slot> = emptySet(),
    val heardText: String? = null,
    /** 마지막으로 차에 보낸 여정 */
    val sent: TripDraft? = null,
    val busy: Boolean = false,
    val prepareSent: Boolean = false,
    val toast: String? = null,
    val history: List<TripLog> = emptyList(),
    /** 여정 입력에서 말로 바꾼 것들 (새 것이 위) */
    val changes: List<ChangeLine> = emptyList(),
    /** 구성원 탭: 미리 적어 둔 사람 · 짐 */
    val members: List<Member> = emptyList(),
    val luggage: List<LuggageItem> = emptyList(),
    /** 이번 여정에 넣은 구성원 · 짐 id (넣은 순서) */
    val picked: List<Long> = emptyList(),
    val ai: AiUi = AiUi(),
    /** 폰에 AI 모델이 받아져 있는지 */
    val aiInstalled: Boolean = false,
    /** AI 모델이 미리 떠 있는지 (바로 답할 수 있음) */
    val aiReady: Boolean = false,
    /** 좌석 기억 (히스토리 탭): 누가 어느 자리에 어떻게 앉았는지 */
    val seatMemory: List<SeatRecord> = emptyList(),
    /** 다녀온 여정 (차에서 내리기 직전 마지막 상태) */
    val rides: List<RideRecord> = emptyList(),
    /** 주행 중 "아기 상태 알리기" 화면이 열려 있음 (탭 위 주행 중 띠에서 엶) */
    val careOpen: Boolean = false,
    /** 아기 상태 버튼 (기본 3개 + 보호자가 더한 것) */
    val careButtons: List<CareButton> = CareDefaults.buttons,
    /** 고른 버튼 id ([보내기] 전) */
    val carePicked: Long? = null,
    /** 할 일을 추천받는 중인 버튼 id */
    val careThinking: Long? = null,
    /** 2D 자리 배치 (여정 입력): 자리마다 넣은 사람 · 트렁크 짐. 여정(draft)은 여기서 셈 */
    val layout: SeatLayout = SeatLayout(),
    /** AI 자세를 차에 적용하는 중 (끝낸 좌석, 전체 좌석) */
    val applying: Pair<Int, Int>? = null,
) {
    /** 차가 우리가 보낸 여정을 받아 세팅 계획을 세웠는지 */
    val carReceived: Boolean
        get() {
            val s = status ?: return false
            val t = s.trip ?: return false
            val mine = sent ?: return false
            return s.tripSource == "휴대폰" && sameTrip(t, mine)
        }
    val draftChanged: Boolean get() = sent != null && !sameTrip(draft, sent)
}

fun sameTrip(a: TripDraft, b: TripDraft) =
    a.destination == b.destination && a.seniors == b.seniors && a.adults == b.adults && a.children == b.children &&
        a.infants == b.infants && a.strollers == b.strollers && a.suitcases == b.suitcases && a.babyBags == b.babyBags

class PhoneVm(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("everyseat_phone", Context.MODE_PRIVATE)
    private val relay = Relay(BuildConfig.RELAY_URL)
    private val llm = SeatLlm(app)
    private val _ui = MutableStateFlow(PhoneUi())
    val ui: StateFlow<PhoneUi> = _ui
    private var poll: Job? = null
    private var wasDriving = false

    /** [출발 준비] 를 보낸 뒤, 차가 좌석을 다 맞추면 좌석 기억에 남길 배치 */
    private var memoryPlan: Map<String, String>? = null

    /** 여정 기억: 출발한 시각 · 본 경로 거리 · 이번 여정의 "출발 준비" 기록 시각 (내리기 전 기록으로 바꿈) */
    private var rideStartAt: Long? = null
    private var rideKm: Double? = null
    private var prepRecordAt: Long? = null

    init {
        // 켜면 항상 P-1 시작 화면. 차가 이미 등록돼 있으면 시작 버튼에서 차 등록을 건너뜀
        _ui.update {
            it.copy(
                code = prefs.getString(KEY_CODE, null), history = loadHistory(),
                members = MemberStore.decode(prefs.getString(KEY_MEMBERS, null)),
                luggage = MemberStore.decodeLuggage(prefs.getString(KEY_LUGGAGE, null)),
                aiInstalled = llm.installed,
                seatMemory = SeatMemoryStore.decode(prefs.getString(KEY_SEAT_MEMORY, null)),
                rides = RideStore.decode(prefs.getString(KEY_RIDES, null)),
                careButtons = CareStore.decode(prefs.getString(KEY_CARE, null)),
            ).let { u ->
                // 저장된 자리 배치가 있으면 여정도 그 배치로. 비어 있으면 아기를 카시트 자리에 미리 넣어 둠
                val l = withBaby(LayoutStore.decode(prefs.getString(KEY_LAYOUT, null)), u.members)
                if (l.isEmpty) u else u.copy(layout = l, draft = l.draft, picked = l.picked)
            }
        }
    }

    // ---- P-1 · P-2 연결 ------------------------------------------------------------------

    /**
     * P-1 [시작하기]. 계정 로그인은 없음 (휴대폰과 차는 6자리 코드로만 이어짐).
     * 등록한 차가 있으면 홈으로, 없으면 차 연결로.
     */
    fun start() {
        // 처음이면 아기 정보부터 (아기를 위한 앱). 건너뛰었거나 이미 있으면 바로 다음으로
        if (!prefs.getBoolean(KEY_BABY_ASKED, false) && _ui.value.members.none { it.who == Who.BABY }) {
            _ui.update { it.copy(stage = Stage.BABY) }
            return
        }
        afterBaby()
    }

    /** 아기 첫 설정 저장 (null = 나중에 할게요). 다른 사람은 구성원 탭에서 더함 */
    fun saveBaby(m: Member?) {
        if (m != null && !addMember(m)) return
        prefs.edit().putBoolean(KEY_BABY_ASKED, true).apply()
        afterBaby()
    }

    private fun afterBaby() {
        if (prefs.getString(KEY_CODE, null) != null) {
            _ui.update { it.copy(stage = Stage.MAIN, tab = Tab.HOME) }
            startPolling()
        } else {
            _ui.update { it.copy(stage = Stage.CONNECT, connectError = null) }
        }
    }

    fun backToStart() = _ui.update { it.copy(stage = Stage.START) }

    fun backToCode() = _ui.update { it.copy(stage = Stage.CONNECT, agreed = false) }

    fun setCodeInput(s: String) = _ui.update { it.copy(codeInput = s.filter(Char::isDigit).take(6), connectError = null) }

    /** 차 화면 QR(everyseat://pair?code=123456)을 찍었을 때 */
    fun onQr(content: String?) {
        val code = content?.let { Regex("""(\d{6})""").find(it)?.value }
        if (code == null) {
            if (content != null) toast("모두의 자리 연결 QR이 아니에요")
            return
        }
        _ui.update { it.copy(codeInput = code, connectError = null) }
        connect()
    }

    fun setAgreed(on: Boolean) = _ui.update { it.copy(agreed = on) }

    /** P-2 연결됨 · 기능 동의 → 홈 */
    fun finishLink() {
        val code = _ui.value.code ?: return
        if (!_ui.value.agreed) return
        prefs.edit().putString(KEY_CODE, code).apply()
        _ui.update { it.copy(stage = Stage.MAIN, tab = Tab.HOME) }
        startPolling()
        sendBabyProfile()
    }

    /**
     * 아기 정보를 차로 (babyProfile). 차의 아기 화면(애칭 · 카시트 · 수유 간격)과 맞추려는 선택 메시지.
     * 차 앱이 아직 모르면 무시함.
     */
    fun sendBabyProfile() {
        val b = _ui.value.members.firstOrNull { it.who == Who.BABY } ?: return
        if (_ui.value.code == null) return
        val msg = JSONObject().put("type", "babyProfile").put("name", b.name).apply {
            b.ageMonths?.let { put("ageMonths", it) }
            b.heightCm?.let { put("heightCm", it) }
            b.weightKg?.let { put("weightKg", it) }
            b.hasCarSeat?.let { put("hasCarSeat", it) }
            b.carSeatDir?.let { put("carSeatDir", it.name) }
            b.carSeatAngle?.let { put("carSeatAngle", it) }
            b.feedMin?.let { put("feedEveryMin", it) }
        }
        send(msg, "아기 정보를 차에 보냈어요")
    }

    fun connect() {
        val code = _ui.value.codeInput
        if (code.length != 6 || _ui.value.connecting) return
        _ui.update { it.copy(connecting = true, connectError = null) }
        viewModelScope.launch {
            val r = relay.pair(code)
            when {
                r.code == 0 -> _ui.update { it.copy(connecting = false, connectError = "중계 서버에 닿지 않아요 · 휴대폰 USB 연결과 PC 의 phone 단계를 확인하세요") }
                r.body?.optBoolean("ok") == true ->
                    _ui.update { it.copy(connecting = false, stage = Stage.LINKED, code = code, carOnline = true, agreed = false) }
                r.body?.optString("error") == "car_offline" ->
                    _ui.update { it.copy(connecting = false, connectError = "차 앱이 잠시 응답하지 않아요 · 몇 초 뒤 다시 눌러 주세요") }
                else -> _ui.update { it.copy(connecting = false, connectError = "차 화면의 코드와 달라요 · 차 화면 오른쪽 위 숫자를 확인하세요") }
            }
        }
    }

    fun disconnect() {
        poll?.cancel()
        prefs.edit().remove(KEY_CODE).apply()
        cancelApply(null)
        _ui.value = PhoneUi(stage = Stage.CONNECT, history = _ui.value.history, careButtons = _ui.value.careButtons, layout = _ui.value.layout)
    }

    private fun startPolling() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive) {
                val code = _ui.value.code ?: break
                val r = relay.status(code)
                when (r.code) {
                    0 -> _ui.update { it.copy(relayReachable = false, carOnline = false) }
                    404 -> {
                        // 중계 서버가 다시 켜졌으면 연결 기록이 없음: 같은 코드로 다시 연결 (차 코드는 차에 저장돼 그대로)
                        val p = relay.pair(code)
                        _ui.update { it.copy(relayReachable = p.code != 0, carOnline = p.body?.optBoolean("ok") == true) }
                    }
                    else -> {
                        val body = r.body
                        val st = body?.optJSONObject("status")?.takeIf { it.length() > 0 }?.let { runCatching { CarStatus.parse(it) }.getOrNull() }
                        _ui.update { u ->
                            var tab = u.tab
                            if (st != null && st.driving && !wasDriving) tab = Tab.DRIVE // 출발하면 주행 중 탭(P-8)으로
                            if (st != null) wasDriving = st.driving
                            u.copy(relayReachable = true, carOnline = body?.optBoolean("car") == true, status = st ?: u.status, tab = tab)
                        }
                        // 출발 준비가 끝나면 그때 좌석 위치로 기억
                        val plan = memoryPlan
                        if (plan != null && st?.run?.finished == true) {
                            memoryPlan = null
                            prepRecordAt = rememberSeats(plan, st, "출발 준비")
                        }
                        if (st != null) watchRide(st)
                        if (st != null) stepApply(st)
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    // ---- P-4 여정 --------------------------------------------------------------------------

    /** 말(또는 적은 글) 한 문장 → 여정에 반영하고, 바뀐 것을 알림처럼 한 줄씩 쌓음 */
    fun onHeard(text: String) {
        val u = _ui.value
        // "다 지워" · "처음부터" · "초기화" (사람 · 짐 낱말 없이) → 처음부터
        if (RESET_WORDS.containsMatchIn(text) && TripParser.parse(text, u.draft, u.members) == null) {
            resetDraft()
            _ui.update { it.copy(heardText = text) }
            toast("처음부터 다시 적을게요")
            return
        }
        // "우리 가족" · "평소처럼" → 구성원 전원과 짐 전부
        if (FAMILY_WORDS.containsMatchIn(text) && (u.members.isNotEmpty() || u.luggage.isNotEmpty())) {
            setDraft(familyTrip(u.members, u.luggage), text, u.members.map { it.id } + u.luggage.map { it.id })
            return
        }
        val r = TripParser.apply(text, u.draft, u.members)
        if (r == null) {
            toast("알아들은 사람이나 짐이 없어요 · 예: 아이 한 명 추가, 캐리어 빼 줘")
            _ui.update { it.copy(heardText = text) }
            return
        }
        val (draft, changes) = r
        if (changes.isEmpty()) {
            toast("이미 그렇게 돼 있어요")
            _ui.update { it.copy(heardText = text) }
            return
        }
        // 이름을 말한 구성원은 넣은 사람으로 표시 (빼라고 했으면 뺌). 알림 줄도 "이봄 추가 (아기)" 처럼 이름으로
        var picked = u.picked
        val said = u.members.filter { it.name.length >= 2 && text.contains(it.name) }
        for (m in said) {
            val up = TripParser.count(draft, m.kind) > TripParser.count(u.draft, m.kind)
            picked = if (up) (picked - m.id) + m.id else picked - m.id
        }
        setDraft(draft, text, picked, names = said.groupBy({ it.kind }, { it.name }))
    }

    /** 새 여정으로 바꾸고, 바뀐 칸을 알림 줄로 쌓음. heard 가 null 이면 버튼으로 바꾼 것 */
    private fun setDraft(draft: TripDraft, heard: String?, picked: List<Long>, lineText: String? = null, names: Map<Slot, List<String>> = emptyMap()) {
        val base = _ui.value.draft
        val changes = Slot.entries.filter { it != Slot.DEST && TripParser.count(base, it) != TripParser.count(draft, it) }
            .map { TripParser.Change(it, TripParser.count(base, it), TripParser.count(draft, it)) }
        if (changes.isEmpty() && heard != null) toast("이미 그렇게 돼 있어요")
        val now = System.currentTimeMillis()
        val lines = if (lineText != null && changes.isNotEmpty()) {
            listOf(ChangeLine(now, lineText, heard.orEmpty(), changes.first().after > changes.first().before))
        } else {
            changes.mapIndexed { i, c ->
                val who = names[c.slot]?.joinToString(", ")
                val text = if (who == null) c.text else "$who ${if (c.after > c.before) "추가" else "빠짐"} (${slotLabel(c.slot)})"
                ChangeLine(now + i, text, heard.orEmpty(), c.after > c.before)
            }.reversed()
        }
        _ui.update {
            it.copy(
                draft = draft, heardText = heard ?: it.heardText,
                picked = normalize(picked, draft, it),
                changes = (lines + it.changes).take(MAX_CHANGES),
            )
        }
    }

    /** 구성원 · 짐 id → 여정 칸 */
    private fun slotOf(u: PhoneUi, id: Long): Slot? = u.members.firstOrNull { it.id == id }?.kind ?: u.luggage.firstOrNull { it.id == id }?.kind

    /** 넣은 구성원 · 짐이 그 칸 수보다 많으면 나중에 넣은 것부터 뺌 (× · "아이 빼" 로 줄었을 때) */
    private fun normalize(picked: List<Long>, d: TripDraft, u: PhoneUi): List<Long> {
        val out = mutableListOf<Long>()
        for (id in picked) {
            val k = slotOf(u, id) ?: continue
            if (out.count { slotOf(u, it) == k } < TripParser.count(d, k)) out += id
        }
        return out
    }

    /** 여정 입력의 구성원 · 짐 칩: 누르면 넣고, 다시 누르면 뺌 */
    fun togglePreset(id: Long) {
        val u = _ui.value
        val k = slotOf(u, id) ?: return
        val name = u.members.firstOrNull { it.id == id }?.name ?: u.luggage.first { it.id == id }.name
        val n = TripParser.count(u.draft, k)
        if (id in u.picked) {
            setDraft(TripParser.with(u.draft, k, (n - 1).coerceAtLeast(0)), null, u.picked - id, "$name 빠짐")
        } else {
            setDraft(TripParser.with(u.draft, k, (n + 1).coerceAtMost(6)), null, u.picked + id, "$name 추가 (${slotLabel(k)})")
        }
    }

    /** [우리 가족 모두]: 구성원 전원 + 짐 전부 */
    fun addFamily() {
        val u = _ui.value
        setDraft(familyTrip(u.members, u.luggage), null, u.members.map { it.id } + u.luggage.map { it.id })
    }

    /**
     * 차로 보낼 짐 부피(L, 빈틈 계산 전). 넣은 짐은 적어 둔 크기로, 나머지(말로 넣은 것)는 종류 기본값으로.
     * 차 앱이 아직 이 값을 안 읽으면 개수로만 계산함.
     */
    private fun luggageLiters(u: PhoneUi, d: TripDraft): Int {
        // 2D 배치에서 넣은 짐이면 그 부피 그대로 (글로 적은 짐 포함)
        if (!u.layout.isEmpty && sameTrip(d, u.layout.draft)) return u.layout.luggageLiters
        var total = 0
        for (slot in listOf(Slot.STROLLER, Slot.SUITCASE, Slot.BABY_BAG)) {
            val items = u.picked.mapNotNull { id -> u.luggage.firstOrNull { it.id == id && it.kind == slot } }.take(TripParser.count(d, slot))
            val rest = TripParser.count(d, slot) - items.size
            val def = when (slot) {
                Slot.STROLLER -> Bag.STROLLER.defaultLiters
                Slot.SUITCASE -> Bag.SUITCASE.defaultLiters
                else -> Bag.BABY_BAG.defaultLiters
            }
            total += items.sumOf { it.estLiters } + rest * def
        }
        return total
    }

    // ---- AI 좌석 추천 (3D 시뮬 탭) -----------------------------------------------------------

    /**
     * 폰 안의 작은 LLM 이 좌석 배치안을 만들고 규칙이 검사함.
     * 1) 규칙을 넣은 프롬프트로 한 번 → 2) 규칙에 걸리면 이유를 알려 주고 한 번 더 → 3) 그래도 안 되면 규칙 배치.
     * 안전 규칙(HARD)을 어긴 AI 안은 어떤 경우에도 고르지 않음.
     */
    fun recommendSeats() {
        val u = _ui.value
        if (u.ai.stage in listOf(AiStage.DOWNLOADING, AiStage.LOADING, AiStage.THINKING)) return
        val trip = u.sent ?: u.draft
        val riders = ridersOf(trip, u.picked, u.members)
        val seats = u.status?.seats?.map { it.id }?.ifEmpty { null } ?: listOf("R1_LEFT", "R1_RIGHT", "R2_LEFT", "R2_RIGHT")
        val used = (luggageLiters(u, trip) / 0.75).toInt()
        val trunk = (u.status?.trunk ?: TrunkState.guess(u.status?.seats.orEmpty(), trip)).liters
        val mem = planMemory(u, trip, riders)
        val memLines = mem.summary(riders)
        fun check(plan: Map<String, String>, source: String, raw: String = "") =
            CheckedPlan(plan, PlanRules.check(plan, riders, seats, used, trunk), source, raw, usual = mem.match(plan, riders).takeIf { it.second > 0 })
        val prefer = mem.prefer(riders)
        val rulePlan = check(PlanRules.fallback(riders, seats, prefer), if (prefer.isEmpty()) "규칙" else "규칙 · 기억")
        if (riders.none { !it.driver }) {
            _ui.update { it.copy(ai = AiUi(AiStage.DONE, plans = listOf(rulePlan), chosen = rulePlan, note = "운전자만 타서 AI 없이 정했어요")) }
            return
        }
        if (!llm.installed) {
            val note = if (prefer.isEmpty()) "AI 모델이 아직 없어 규칙으로 정했어요" else "AI 모델이 아직 없어 기억과 규칙으로 정했어요"
            _ui.update {
                it.copy(ai = AiUi(AiStage.DONE, plans = listOf(rulePlan), chosen = rulePlan, note = note, memory = memLines, guess = SeatGuessRules.guess(rulePlan.seats, riders, u.seatMemory)))
            }
            return
        }
        _ui.update { it.copy(ai = AiUi(AiStage.LOADING, memory = memLines)) }
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            var loadedAt = started
            val plans = mutableListOf<CheckedPlan>()
            val failure = runCatching {
                llm.chat(onLoaded = {
                    loadedAt = System.currentTimeMillis()
                    _ui.update { it.copy(aiReady = true, ai = it.ai.copy(stage = AiStage.THINKING)) }
                }) { send ->
                    val first = send(PlanPrompt.build(riders, seats, mem))
                    val a = check(PlanParse.parse(first, riders), "AI", first)
                    plans += a
                    if (!a.passed) {
                        // 빠진 사람 · 어긴 자리만 규칙이 고쳐 봄 (AI 가 정한 자리는 그대로)
                        val (fixed, fixes) = PlanRules.complete(a.seats, riders, seats)
                        val c = check(fixed, "AI + 규칙 보완", first).copy(fixes = fixes)
                        if (c.passed) plans += c
                    }
                    if (plans.none { it.passed }) {
                        val second = send(PlanPrompt.retry(a.issues, riders, seats))
                        val b = check(PlanParse.parse(second, riders), "AI 다시", second)
                        // 같은 답을 또 내면 따로 보여 주지 않음
                        if (b.seats != a.seats) plans += b
                    }
                }
            }.exceptionOrNull()
            val aiOk = plans.firstOrNull { it.passed }
            // 기억이 있는데 AI 안이 평소 자리와 덜 맞으면 기억대로 (AI 안은 "다른 안" 으로 남김)
            val aiOffHabit = aiOk != null && (aiOk.usual?.first ?: 0) < (rulePlan.usual?.first ?: 0)
            val chosen = if (aiOk == null || aiOffHabit) rulePlan else aiOk
            val note = when {
                failure != null -> "AI를 돌리지 못해 규칙으로 정했어요 (${failure.message?.take(60) ?: failure.javaClass.simpleName})"
                aiOk == null -> "AI 안이 안전 규칙에 걸려서 규칙으로 정했어요"
                aiOffHabit -> "AI 안이 평소 앉던 자리와 달라서 기억대로 정했어요"
                aiOk.fixes.isNotEmpty() -> "AI 안에서 규칙에 안 맞는 부분만 고쳤어요"
                aiOk.warnings.isEmpty() -> "AI 안이 규칙 검사를 통과했어요"
                else -> "AI 안이 규칙을 통과했어요 · 확인할 점 ${aiOk.warnings.size}개"
            }
            val all = if (chosen === rulePlan) plans + rulePlan else plans
            val now = System.currentTimeMillis()
            val secs = ((now - started) / 1000).toInt()
            _ui.update {
                it.copy(
                    aiReady = llm.ready,
                    ai = AiUi(
                        AiStage.DONE, plans = all, chosen = chosen, note = note, seconds = secs, memory = memLines,
                        guess = SeatGuessRules.guess(chosen.seats, riders, it.seatMemory),
                        loadSec = ((loadedAt - started) / 1000).toInt(), thinkSec = ((now - loadedAt) / 1000).toInt(),
                    ),
                )
            }
        }
    }

    /** 좌석 계획 카드가 보일 때: 여정 · 넣은 구성원이 지난번과 다를 때만 자동으로 자리 고르기 */
    private var autoKey: Int? = null

    fun autoPlan() {
        val u = _ui.value
        val key = listOf(u.sent ?: u.draft, u.picked, u.aiInstalled).hashCode()
        if (key == autoKey && u.ai.stage != AiStage.IDLE) return
        autoKey = key
        recommendSeats()
    }

    /** 추천에 쓸 기억: 사람별 자주 앉는 자리 + 사람 · 짐 · 목적지 · 거리가 비슷한 지난 여정 */
    private fun planMemory(u: PhoneUi, trip: TripDraft, riders: List<Rider>): PlanMemory {
        val q = RideQuery(
            riders.filter { !it.driver }.map { it.label }, luggageLiters(u, trip),
            u.status?.destinationName.orEmpty(), u.status?.routeKm,
        )
        return PlanMemory(personHabits(u.seatMemory), RideMatch.similar(q, u.rides))
    }

    /** 다른 안을 고름 (AI 안 ↔ 기억 · 규칙 안). 안전 규칙을 통과한 안만 */
    fun choosePlan(plan: CheckedPlan) {
        if (!plan.passed) return
        val u = _ui.value
        val riders = ridersOf(u.sent ?: u.draft, u.picked, u.members)
        _ui.update { it.copy(ai = it.ai.copy(chosen = plan, note = "고른 안: ${plan.source}", guess = SeatGuessRules.guess(plan.seats, riders, u.seatMemory))) }
    }

    /** [AI 모델 받기] (343MB) */
    fun downloadModel() {
        if (_ui.value.ai.stage == AiStage.DOWNLOADING) return
        _ui.update { it.copy(ai = AiUi(AiStage.DOWNLOADING)) }
        viewModelScope.launch {
            val ok = llm.download { p -> _ui.update { it.copy(ai = it.ai.copy(progress = p)) } }
            _ui.update { it.copy(aiInstalled = llm.installed, ai = AiUi(note = if (ok) "AI 모델을 받았어요" else "받지 못했어요 · 와이파이를 확인해 주세요")) }
        }
    }

    /** 앱으로 돌아왔을 때 모델이 생겼는지 다시 봄 (adb 로 넣은 경우) */
    fun refreshAiInstalled() = _ui.update { it.copy(aiInstalled = llm.installed) }

    /** 여정 입력 · 3D 시뮬에 들어오면 AI 모델을 미리 띄워 둠 (누를 때 기다리지 않게) */
    fun warmUpAi() {
        if (!llm.installed || llm.ready) return
        viewModelScope.launch {
            llm.warmUp()
            _ui.update { it.copy(aiReady = llm.ready) }
        }
    }

    override fun onCleared() {
        llm.release()
    }

    // ---- 구성원 탭 --------------------------------------------------------------------------

    /** [사람 추가] 칸들. 이름 말고는 몰라도 됨 */
    fun addMember(m: Member): Boolean {
        if (_ui.value.members.any { it.name == m.name }) {
            toast("같은 이름이 이미 있어요")
            return false
        }
        saveMembers(_ui.value.members + m)
        return true
    }

    fun removeMember(id: Long) {
        saveMembers(_ui.value.members.filter { it.id != id })
        _ui.update { it.copy(picked = it.picked - id) }
    }

    /** [짐 추가] 칸들. 이름 말고는 몰라도 됨 */
    fun addLuggage(name: String, size: String, weight: String, note: String): Boolean {
        val l = PresetParser.luggageFrom(name, size, weight, note) ?: return false
        val list = _ui.value.luggage + l
        prefs.edit().putString(KEY_LUGGAGE, MemberStore.encodeLuggage(list)).apply()
        _ui.update { it.copy(luggage = list) }
        return true
    }

    fun removeLuggage(id: Long) {
        val list = _ui.value.luggage.filter { it.id != id }
        prefs.edit().putString(KEY_LUGGAGE, MemberStore.encodeLuggage(list)).apply()
        _ui.update { it.copy(luggage = list, picked = it.picked - id) }
    }

    private fun saveMembers(list: List<Member>) {
        prefs.edit().putString(KEY_MEMBERS, MemberStore.encode(list)).apply()
        _ui.update { it.copy(members = list) }
        // 아기를 처음 넣었고 다음 여정이 비어 있으면 아기를 카시트 자리에
        if (_ui.value.layout.isEmpty) withBaby(SeatLayout(), list).takeIf { !it.isEmpty }?.let(::setLayout)
    }

    /** 여정 입력 칩의 [×]: 그 칸에서 하나 뺌 (아이 2 → 아이 1). 알림 줄에는 "직접 뺌" 으로 남김 */
    fun removeOne(slot: Slot) {
        val base = _ui.value.draft
        val now = TripParser.count(base, slot)
        if (now <= 0) return
        val change = TripParser.Change(slot, now, now - 1)
        val line = ChangeLine(System.currentTimeMillis(), change.text, "", false)
        val draft = TripParser.with(base, slot, now - 1)
        _ui.update { it.copy(draft = draft, picked = normalize(it.picked, draft, it), changes = (listOf(line) + it.changes).take(MAX_CHANGES)) }
    }

    /** 여정 입력 [처음부터] */
    fun resetDraft() = _ui.update { it.copy(draft = TripDraft(), changes = emptyList(), heardText = null, picked = emptyList()) }

    fun edit(change: (TripDraft) -> TripDraft) = _ui.update { it.copy(draft = change(it.draft)) }

    /**
     * [차로 보내기] 한 번으로 끝 (11/14 사용자: "누르면 그냥 해줘"):
     *  1) 2D 배치로 AI 자세를 정함 (폰 LLM → 규칙 검사. 모델이 없으면 기억 · 규칙)
     *  2) 여정 + 고른 자리 + 자세(seatPlan)를 차로
     *  3) 바로 출발 준비(prepare) → 차가 P단 · 5초 카운트다운 · [취소] · 앉은 좌석 · 문 확인 뒤 좌석을 맞춤
     * 카시트 공간 · 등받이 세우기는 차의 규칙(SeatPlanner)이 정하고, 자세는 겹치지 않는 축에만 씀.
     */
    fun sendTrip() {
        val u = _ui.value
        val code = u.code ?: return
        if (u.busy || !u.carOnline) return
        // 목적지는 휴대폰에서 다루지 않음 (차 내비가 정함)
        val d = u.draft
        val l = u.layout.takeIf { !it.isEmpty && sameTrip(d, it.draft) }
        val needs = l?.seatNeeds?.map { it.name } ?: u.picked.mapNotNull { id -> u.members.firstOrNull { it.id == id }?.need?.name }
        val plan = l?.plan ?: PlanRules.fallback(ridersOf(d, u.picked, u.members), carSeatIds(u))
        val riders = l?.riders ?: ridersOf(d, u.picked, u.members)
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            // 1) AI 자세
            val ai = computePosture(u, plan, riders, d)
            postureKey = listOf(plan, u.aiInstalled).hashCode()
            _ui.update { it.copy(ai = ai, aiReady = llm.ready) }
            // 2) 여정 + 자리 + 자세
            val msg = JSONObject()
                .put("type", "trip")
                .put("destination", d.destination)
                .put("seniors", d.seniors).put("adults", d.adults).put("children", d.children).put("infants", d.infants)
                .put("strollers", d.strollers).put("suitcases", d.suitcases).put("babyBags", d.babyBags)
                .put("caregiverNextToBaby", d.caregiverNextToBaby)
                // 선택 키 (차 앱이 몰라도 됨): 적어 둔 크기로 셈한 짐 부피 · 넣은 아이들의 카시트 단계
                .put("luggageLiters", luggageLiters(u, d))
                .put("seatNeeds", org.json.JSONArray(needs))
                .apply {
                    // 선택 키: 보호자가 2D 에서 직접 고른 자리 + AI 자세 (0..100)
                    l?.let { lay ->
                        put(
                            "seatPlan",
                            org.json.JSONArray(
                                lay.seats.filterKeys { it != SeatLayout.DRIVER }.map { (seat, p) ->
                                    val g = ai.guess[seat]
                                    JSONObject().put("seat", seat).put("label", p.label).put("who", p.who.name).apply {
                                        p.need?.let { put("need", it.name) }
                                        g?.foreAft?.let { put("foreAft", it) }
                                        g?.let { put("backrest", it.backrest) }
                                    }
                                },
                            ),
                        )
                    }
                }
            val r = relay.send(code, msg)
            if (r.code != 200) {
                _ui.update { it.copy(busy = false) }
                toast(if (r.code == 0) "중계 서버에 닿지 않아요 · USB 연결을 확인하세요" else "차와 연결이 끊겼어요 · 다시 연결해 주세요")
                return@launch
            }
            val history = (listOf(TripLog(System.currentTimeMillis(), d, plan)) + _ui.value.history).take(MAX_HISTORY)
            saveHistory(history)
            _ui.update { it.copy(sent = d, draft = d, prepareSent = false, history = history) }
            // 3) 바로 출발 준비 (차는 받은 순서대로 처리: 여정 → 계획 → 좌석 맞추기)
            val p = relay.send(code, JSONObject().put("type", "prepare"))
            _ui.update { it.copy(busy = false, editing = false, tab = Tab.SIM, prepareSent = p.code == 200) }
            if (p.code == 200) {
                memoryPlan = plan
                toast("차로 보냈어요 · 5초 뒤 좌석이 움직여요 (차 화면에서 취소할 수 있어요)")
            } else {
                toast("여정은 보냈는데 출발 준비를 못 보냈어요 · 홈에서 [출발 준비]를 눌러 주세요")
            }
        }
    }

    private fun carSeatIds(u: PhoneUi): List<String> = u.status?.seats?.map { it.id }?.ifEmpty { null } ?: listOf("R1_LEFT", "R1_RIGHT", "R2_LEFT", "R2_RIGHT")

    // ---- 2D 자리 배치 (여정 입력) ----------------------------------------------------------------

    /** 자리에 사람을 넣음. 안전 규칙에 걸리면 넣지 않고 이유를 알림 */
    fun placeSeat(seat: String, p: Placed): Boolean {
        LayoutRules.blocked(seat, p)?.let {
            toast(it)
            return false
        }
        setLayout(_ui.value.layout.put(seat, p))
        return true
    }

    fun clearSeat(seat: String) = setLayout(_ui.value.layout.clear(seat))

    /** 트렁크: 구성원 탭의 짐 (누르면 넣고, 다시 누르면 뺌) */
    fun toggleTrunkLuggage(item: LuggageItem) {
        val l = _ui.value.layout
        val have = l.trunk.firstOrNull { it.luggageId == item.id }
        setLayout(if (have != null) l.removeTrunk(have.id) else l.addTrunk(TrunkPut.of(item)))
    }

    /** 트렁크: 글로 적은 짐 ("24인치 캐리어") */
    fun addTrunkText(text: String): Boolean {
        val t = TrunkPut.typed(text) ?: return false
        setLayout(_ui.value.layout.addTrunk(t))
        return true
    }

    fun removeTrunk(id: Long) = setLayout(_ui.value.layout.removeTrunk(id))

    /**
     * [지난번처럼 앉기] 후보: 다녀온 여정(내리기 전 배치) 먼저, 그다음 차로 보낸 여정. 같은 배치는 한 번만, 최대 3개
     */
    fun pastTrips(): List<PastTrip> {
        val u = _ui.value
        val fmt = java.text.SimpleDateFormat("M월 d일 (E)", java.util.Locale.KOREAN)
        val rides = u.rides.filter { r -> r.seats.any { it.value != "운전자" } }.map { r ->
            PastTrip(r.at, "${fmt.format(java.util.Date(r.at))} ${r.destination.ifBlank { "다녀온 여정" }}", r.seats, r.trip)
        }
        val sent = u.history.filter { h -> h.seats.any { it.value != "운전자" } }.map { h ->
            PastTrip(h.at, "${fmt.format(java.util.Date(h.at))} 보낸 여정", h.seats, h.trip)
        }
        return (rides + sent).distinctBy { it.seats }.take(3)
    }

    /** 지난 배치를 그대로 2D 에 채움 (구성원은 이름으로, 짐은 수만큼). 안전 규칙에 걸리는 자리는 뺌 */
    fun sitLikeBefore(p: PastTrip) {
        val l = LayoutFromPast.build(p, _ui.value.members)
        if (l.isEmpty) {
            toast("지난 배치를 불러오지 못했어요")
            return
        }
        setLayout(l)
        toast("지난번처럼 넣었어요 · 바꿀 자리는 눌러서 고쳐요")
    }

    /** [처음부터]: 아기만 카시트 자리에 남기고 비움 */
    fun clearLayout() = setLayout(withBaby(SeatLayout(), _ui.value.members))

    /**
     * 모두의 자리는 아기 동반이 전제: 배치가 비어 있으면 아기(구성원의 첫 아기)를 2열 오른쪽 카시트 자리에 넣어 둠.
     * 보호자가 다른 자리로 옮기거나 뺄 수 있음.
     */
    private fun withBaby(l: SeatLayout, members: List<Member>): SeatLayout {
        if (!l.isEmpty) return l
        val baby = members.firstOrNull { it.who == Who.BABY } ?: return l
        val p = Placed.of(baby)
        return if (LayoutRules.blocked(BABY_SEAT, p) == null) l.put(BABY_SEAT, p) else l
    }

    private fun setLayout(l: SeatLayout) {
        prefs.edit().putString(KEY_LAYOUT, LayoutStore.encode(l)).apply()
        _ui.update { it.copy(layout = l, draft = l.draft, picked = l.picked) }
    }

    // ---- AI 자세 (3D 시뮬): 보호자가 고른 자리마다 등받이 · 앞뒤 ---------------------------------------

    /**
     * 폰 안 LLM 이 자리마다 자세를 내고 규칙이 검사 (카시트 20° · 뒤보기 카시트 앞자리 공간 · 주행 중 35° 까지).
     * 모델이 없거나 답을 못 알아들으면 기억 · 규칙 (SeatGuessRules).
     */
    fun recommendPosture() {
        val u = _ui.value
        if (u.ai.stage in listOf(AiStage.DOWNLOADING, AiStage.LOADING, AiStage.THINKING)) return
        val plan = currentPlan()
        val riders = postureRiders(u)
        viewModelScope.launch {
            val ai = computePosture(u, plan, riders, u.sent ?: u.draft)
            _ui.update { it.copy(ai = ai, aiReady = llm.ready) }
        }
    }

    /** 자세 계산 (3D 시뮬 [다시 계산] · [차로 보내기] 같이 씀). 계산하는 동안 화면에 "AI 계산 중" */
    private suspend fun computePosture(u: PhoneUi, plan: Map<String, String>, riders: List<Rider>, trip: TripDraft): AiUi {
        if (riders.none { !it.driver }) return AiUi(AiStage.DONE, note = "운전자만 타요 · 운전석은 직접 맞춰 주세요")
        // 검색 (RAG): 사람 · 짐 · 목적지 · 거리가 비슷한 지난 여정 (RideMatch 55점 이상, 가장 비슷한 것 하나)
        val similar = RideMatch.similar(
            RideQuery(riders.filter { !it.driver }.map { it.label }, luggageLiters(u, trip), u.status?.destinationName.orEmpty(), u.status?.routeKm),
            u.rides,
        ).firstOrNull()
        // 참고한 기억: 비슷한 여정 + 사람마다 마지막 자세 ("이봄 지난번 · 앞뒤 50% · 등받이 20° (2열 오른쪽)")
        val memLines = PlanMemory(similar = listOfNotNull(similar)).summary(emptyList()) + riders.filter { !it.driver }.mapNotNull { r ->
            u.seatMemory.filter { it.who == r.label && (it.backrest != null || it.foreAft != null) }.maxByOrNull { it.at }
                ?.let { "${r.label} 지난번 · ${positionText(it.foreAft, it.backrest)} (${seatName(it.seat)})" }
        }.take(5)
        if (!llm.installed) {
            val r = PostureRules.check(emptyMap(), plan, riders, u.seatMemory, similar, driverNow(u))
            val how = if (similar != null) "비슷한 여정 · 기억 · 규칙" else "기억 · 규칙"
            return AiUi(AiStage.DONE, guess = r.guess, note = "AI 모델이 없어 ${how}으로 정했어요", memory = memLines)
        }
        _ui.update { it.copy(ai = AiUi(AiStage.THINKING, memory = memLines)) }
        val started = System.currentTimeMillis()
        val result = runCatching {
            PosturePrompt.parse(llm.askOnce(PosturePrompt.SYSTEM, PosturePrompt.build(plan, riders, u.seatMemory, similar), maxOut = 80))
        }
        val r = PostureRules.check(result.getOrDefault(emptyMap()), plan, riders, u.seatMemory, similar, driverNow(u))
        val note = when {
            result.isFailure -> "AI를 돌리지 못해 기억 · 규칙으로 정했어요"
            r.aiSeats == 0 -> "AI 답을 알아듣지 못해 기억 · 규칙으로 정했어요"
            r.fixes.isNotEmpty() -> "AI가 정한 자세에서 안전 규칙에 안 맞는 부분만 고쳤어요"
            else -> "AI가 정한 자세가 규칙 검사를 통과했어요"
        }
        return AiUi(
            AiStage.DONE, guess = r.guess, note = note, fixes = r.fixes, memory = memLines,
            seconds = ((System.currentTimeMillis() - started) / 1000).toInt(),
        )
    }

    /** 차가 알려 준 운전석 지금 자세 (앞뒤, 등받이). 운전할 수 있는 자세인지 볼 때 씀 */
    private fun driverNow(u: PhoneUi): Pair<Int?, Int?>? =
        u.status?.seats?.firstOrNull { it.id == SeatLayout.DRIVER }?.let { it.foreAft to it.backrest }

    /** 3D 시뮬에 들어왔을 때: 배치가 지난번과 다를 때만 자동으로 자세 계산 */
    private var postureKey: Int? = null

    fun autoPosture() {
        val u = _ui.value
        val key = listOf(currentPlan(), u.aiInstalled).hashCode()
        if (key == postureKey && u.ai.stage != AiStage.IDLE) return
        postureKey = key
        recommendPosture()
    }

    /** 자세를 정할 사람: 2D 배치가 있으면 그 사람들, 없으면 여정 칸으로 */
    private fun postureRiders(u: PhoneUi): List<Rider> {
        val trip = u.sent ?: u.draft
        return if (!u.layout.isEmpty && sameTrip(trip, u.layout.draft)) u.layout.riders else ridersOf(trip, u.picked, u.members)
    }

    // ---- [차에 적용]: AI 자세를 좌석 하나씩 차로 (차는 좌석을 맞추는 중이면 다음 요청을 받지 않음) ----------------

    private data class ApplyItem(val seat: String, val fore: Int?, val back: Int?, val keepFore: Int?, val keepBack: Int?)

    private var applyQueue: List<ApplyItem> = emptyList()
    private var applySentAt = 0L
    private var applyStarted = false

    fun applyPosture() {
        val u = _ui.value
        val st = u.status
        if (st == null || !u.carOnline) {
            toast("차와 연결되면 적용할 수 있어요")
            return
        }
        if (st.driving) {
            toast("주행 중에는 좌석을 움직이지 않아요")
            return
        }
        val items = u.ai.guess.filterKeys { it != SeatLayout.DRIVER }.mapNotNull { (seat, g) ->
            val s = st.seats.firstOrNull { it.id == seat } ?: return@mapNotNull null
            val fore = g.foreAft?.takeIf { s.canForeAft }
            val back = g.backrest.takeIf { s.canBackrest }
            if (fore == null && back == null) null else ApplyItem(seat, fore, back, g.foreAft, g.backrest)
        }.sortedBy { it.seat }
        if (items.isEmpty()) {
            toast("차가 움직일 수 있는 좌석이 없어요 · 손으로 맞춰 주세요")
            return
        }
        applyQueue = items
        _ui.update { it.copy(applying = 0 to items.size) }
        sendNextApply()
    }

    private fun sendNextApply() {
        val item = applyQueue.firstOrNull()
        if (item == null) {
            val total = _ui.value.applying?.second ?: 0
            _ui.update { it.copy(applying = null) }
            if (total > 0) toast("AI 자세를 좌석 ${total}개에 적용했어요")
            return
        }
        val code = _ui.value.code ?: return cancelApply(null)
        applyStarted = false
        applySentAt = System.currentTimeMillis()
        val msg = JSONObject().put("type", "seatAdjust").put("seat", item.seat).apply {
            item.fore?.let { put("foreAft", it) }
            item.back?.let { put("backrest", it) }
        }
        viewModelScope.launch {
            val r = relay.send(code, msg)
            if (r.code != 200) {
                cancelApply("차로 보내지 못했어요 · 연결을 확인해 주세요")
                return@launch
            }
            val who = currentPlan()[item.seat] ?: ""
            rememberSeats(mapOf(item.seat to who), _ui.value.status, "AI 자세", mapOf(item.seat to (item.keepFore to item.keepBack)))
        }
    }

    /** 상태를 받을 때마다: 차가 지금 좌석을 다 맞췄으면 다음 좌석 */
    private fun stepApply(st: CarStatus) {
        if (applyQueue.isEmpty()) return
        val run = st.run
        if (!applyStarted) {
            if (run != null && !run.finished) {
                applyStarted = true
            } else if (System.currentTimeMillis() - applySentAt > APPLY_START_MS) {
                cancelApply(if (st.pending > 0) "P단이 되면 차가 좌석을 맞춰요 · 나머지는 그 뒤에 다시 적용해 주세요" else "차가 좌석을 움직이지 않았어요 · 차 화면을 확인해 주세요")
            }
            return
        }
        if (run?.finished != true) return
        if (!run.allOk) {
            cancelApply("좌석 맞추기가 멈췄어요 (취소 · 문 열림 · 앉은 좌석) · 차 화면을 확인해 주세요")
            return
        }
        applyQueue = applyQueue.drop(1)
        _ui.update { it.copy(applying = it.applying?.let { (d, t) -> (d + 1) to t }) }
        sendNextApply()
    }

    private fun cancelApply(reason: String?) {
        applyQueue = emptyList()
        _ui.update { it.copy(applying = null) }
        reason?.let(::toast)
    }

    fun clearHistory() {
        saveHistory(emptyList())
        _ui.update { it.copy(history = emptyList()) }
    }

    private fun saveHistory(list: List<TripLog>) {
        val arr = org.json.JSONArray()
        for (h in list) {
            val d = h.trip
            arr.put(
                JSONObject().put("at", h.at).put("destination", d.destination)
                    .put("seniors", d.seniors).put("adults", d.adults).put("children", d.children).put("infants", d.infants)
                    .put("strollers", d.strollers).put("suitcases", d.suitcases).put("babyBags", d.babyBags)
                    .put("seats", JSONObject().apply { h.seats.forEach { (k, v) -> put(k, v) } }),
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun loadHistory(): List<TripLog> = runCatching {
        val arr = org.json.JSONArray(prefs.getString(KEY_HISTORY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TripLog(
                o.optLong("at"),
                TripDraft(
                    destination = o.optString("destination"),
                    seniors = o.optInt("seniors"), adults = o.optInt("adults"), children = o.optInt("children"), infants = o.optInt("infants"),
                    strollers = o.optInt("strollers"), suitcases = o.optInt("suitcases"), babyBags = o.optInt("babyBags"),
                ),
                o.optJSONObject("seats")?.let { so -> so.keys().asSequence().associateWith { so.getString(it) } }.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    fun openEditor() = _ui.update { it.copy(editing = true) }

    fun closeEditor() = _ui.update { it.copy(editing = false) }

    fun prepare() = send(JSONObject().put("type", "prepare"), "출발 준비를 보냈어요 · 5초 뒤 좌석이 움직여요") {
        _ui.update { it.copy(prepareSent = true) }
        memoryPlan = currentPlan()
    }

    // ---- 좌석 직접 맞추기 · 좌석 기억 ------------------------------------------------------------

    /** 지금 배치: AI 추천에서 고른 안, 없으면 규칙 배치 */
    fun currentPlan(): Map<String, String> {
        val u = _ui.value
        val trip = u.sent ?: u.draft
        // 보호자가 2D 에서 고른 자리가 먼저
        if (!u.layout.isEmpty && sameTrip(trip, u.layout.draft)) return u.layout.plan
        u.ai.chosen?.let { return it.seats }
        val seats = u.status?.seats?.map { it.id }?.ifEmpty { null } ?: listOf("R1_LEFT", "R1_RIGHT", "R2_LEFT", "R2_RIGHT")
        val riders = ridersOf(trip, u.picked, u.members)
        return PlanRules.fallback(riders, seats, planMemory(u, trip, riders).prefer(riders))
    }

    /**
     * 여정 기억: 출발을 보고, 차에서 내리기 직전(도착 · P단) 마지막 상태를 남김.
     * 누가 어디 앉았는지 · 좌석 위치 · 목적지 · 거리 · 걸린 시간 · 짐. 이번 여정의 "출발 준비" 좌석 기록은 이것으로 바꿈.
     */
    private fun watchRide(st: CarStatus) {
        val now = System.currentTimeMillis()
        if (st.driving && rideStartAt == null) rideStartAt = now
        st.routeKm?.let { rideKm = maxOf(rideKm ?: 0.0, it) }
        val start = rideStartAt ?: return
        val parked = st.arrived || (!st.driving && st.gear == "P")
        if (!parked || now - start < MIN_RIDE_MS) return
        rideStartAt = null
        recordRide(st, start, now)
        rideKm = null
    }

    private fun recordRide(st: CarStatus, start: Long, now: Long) {
        val u = _ui.value
        val trip = u.sent ?: u.draft
        val plan = currentPlan()
        val ride = RideRecord(
            at = now,
            destination = st.destinationName,
            km = rideKm,
            minutes = ((now - start) / 60_000).toInt(),
            trip = trip,
            luggageLiters = luggageLiters(u, trip),
            seats = plan,
            positions = plan.keys.associateWith { id -> st.seats.firstOrNull { it.id == id }.let { it?.foreAft to it?.backrest } },
        )
        val rides = (listOf(ride) + u.rides).take(MAX_RIDES)
        prefs.edit().putString(KEY_RIDES, RideStore.encode(rides)).apply()
        _ui.update { it.copy(rides = rides) }
        // 같은 여정의 "출발 준비" 기록은 빼고 내리기 전 상태로 (한 여정 = 한 번으로 셈)
        prepRecordAt?.let { at -> saveSeatMemory(_ui.value.seatMemory.filterNot { it.at == at && it.source == "출발 준비" }) }
        prepRecordAt = null
        rememberSeats(plan, st, "내리기 전")
        toast("내리기 전 좌석을 기억했어요 · 다음 추천에 써요")
    }

    fun clearRides() {
        prefs.edit().remove(KEY_RIDES).apply()
        _ui.update { it.copy(rides = emptyList()) }
    }

    /** 좌석에 앉은 사람의 카시트 등 한 줄 */
    private fun detailOf(u: PhoneUi, who: String): String {
        u.members.firstOrNull { it.name == who }?.let { return it.seatText ?: it.who.label }
        return when {
            who.startsWith("아기") -> SeatNeed.CAR_SEAT.label
            who.startsWith("아이") -> SeatNeed.BOOSTER.label
            else -> ""
        }
    }

    private fun rememberSeats(plan: Map<String, String>, st: CarStatus?, source: String, overrides: Map<String, Pair<Int?, Int?>> = emptyMap()): Long {
        val u = _ui.value
        val now = System.currentTimeMillis()
        val recs = plan.filterValues { it != "운전자" }.map { (seat, who) ->
            val pos = st?.seats?.firstOrNull { it.id == seat }
            val o = overrides[seat]
            SeatRecord(now, seat, who, detailOf(u, who), o?.first ?: pos?.foreAft, o?.second ?: pos?.backrest, source)
        }
        saveSeatMemory((recs + u.seatMemory).take(MAX_SEAT_MEMORY))
        return now
    }

    private fun saveSeatMemory(list: List<SeatRecord>) {
        prefs.edit().putString(KEY_SEAT_MEMORY, SeatMemoryStore.encode(list)).apply()
        _ui.update { it.copy(seatMemory = list) }
    }

    fun clearSeatMemory() = saveSeatMemory(emptyList())

    /**
     * 3D 에서 좌석을 눌러 직접 맞춘 값을 차로 보냄 (seatAdjust). 위치 0..100 (앞뒤 100 = 맨 앞, 등받이 100 = 가장 세움).
     * 차는 P단 · 카운트다운 · 착좌 · 문 확인을 거쳐서만 움직임. 보낸 값은 좌석 기억에도 남김.
     */
    fun sendSeatAdjust(seat: String, foreAft: Int?, backrest: Int?, keepFore: Int? = foreAft, keepBack: Int? = backrest) {
        val who = currentPlan()[seat] ?: ""
        if (foreAft == null && backrest == null) {
            // 차가 못 움직이는 좌석: 보내지 않고 기억만
            rememberSeats(mapOf(seat to who), _ui.value.status, "직접 기록", mapOf(seat to (keepFore to keepBack)))
            toast("이 자리 설정을 기억했어요")
            return
        }
        val msg = JSONObject().put("type", "seatAdjust").put("seat", seat).apply {
            foreAft?.let { put("foreAft", it) }
            backrest?.let { put("backrest", it) }
        }
        send(msg, "차로 보냈어요 · 차에서 확인한 뒤 움직여요") {
            rememberSeats(mapOf(seat to who), _ui.value.status, "직접 조절", mapOf(seat to (keepFore to keepBack)))
        }
    }

    // ---- P-8 주행 중 -----------------------------------------------------------------------

    // ---- 아기 상태 알리기 (탭 위 주행 중 띠 → 버튼 고르기 → [보내기]) -------------------------------

    fun openCare() = _ui.update { it.copy(careOpen = true, carePicked = null) }

    fun closeCare() = _ui.update { it.copy(careOpen = false, carePicked = null) }

    /** 버튼 고르기. 같은 버튼을 다시 누르면 풀림 */
    fun pickCare(id: Long) = _ui.update { it.copy(carePicked = if (it.carePicked == id) null else id) }

    /** 고른 버튼을 차로. 차는 내비 옆에 알리고, 할 일(소리 · 온도 · 노래 · 근처 장소)을 처리함 */
    fun sendCare() {
        val u = _ui.value
        val b = u.careButtons.firstOrNull { it.id == u.carePicked } ?: return
        send(CareMessage.build(b), "차에 알렸어요: ${b.label}") {
            _ui.update { it.copy(careOpen = false, carePicked = null) }
        }
    }

    /** 보호자가 버튼을 더함. 할 일은 바로 추천받음 (AI 모델이 없으면 낱말 규칙) */
    fun addCareButton(emoji: String, label: String): Boolean {
        val name = label.trim().take(12)
        if (name.isEmpty()) return false
        if (_ui.value.careButtons.any { it.label == name }) {
            toast("같은 이름의 버튼이 있어요")
            return false
        }
        val id = (_ui.value.careButtons.maxOfOrNull { it.id } ?: 0) + 1
        val b = CareButton(id, emoji, name, steps = CareRules.suggest(name), source = CareSource.RULE)
        saveCare(_ui.value.careButtons + b)
        _ui.update { it.copy(carePicked = id) }
        suggestCare(id)
        return true
    }

    /** 보호자가 할 일을 직접 고침 */
    fun setCareSteps(id: Long, steps: List<CareStep>) =
        saveCare(_ui.value.careButtons.map { if (it.id == id) it.copy(steps = CareAction.clean(steps), source = CareSource.USER) else it })

    /** 기본 버튼은 지우지 않음 */
    fun removeCareButton(id: Long) {
        saveCare(_ui.value.careButtons.filter { it.id != id || !it.custom })
        _ui.update { it.copy(carePicked = if (it.carePicked == id) null else it.carePicked) }
    }

    /** 할 일 추천: 폰 안 LLM 이 있으면 LLM, 없거나 답을 못 알아들으면 낱말 규칙 */
    fun suggestCare(id: Long) {
        val b = _ui.value.careButtons.firstOrNull { it.id == id } ?: return
        if (_ui.value.careThinking != null) return
        if (!llm.installed) {
            val rule = CareRules.suggest(b.label)
            if (rule.isEmpty()) toast("추천할 일을 못 찾았어요 · 직접 골라 주세요")
            else saveCare(_ui.value.careButtons.map { if (it.id == id) it.copy(steps = rule, source = CareSource.RULE) else it })
            return
        }
        _ui.update { it.copy(careThinking = id) }
        viewModelScope.launch {
            val ai = runCatching { CarePrompt.parse(llm.askOnce(CarePrompt.SYSTEM, CarePrompt.build(b.label))) }.getOrDefault(emptyList())
            val (steps, source) = when {
                ai.isNotEmpty() -> ai to CareSource.AI
                else -> CareRules.suggest(b.label) to CareSource.RULE
            }
            _ui.update { it.copy(careThinking = null, aiReady = llm.ready) }
            if (steps.isEmpty()) {
                toast("추천할 일을 못 찾았어요 · 직접 골라 주세요")
                return@launch
            }
            saveCare(_ui.value.careButtons.map { if (it.id == id) it.copy(steps = steps, source = source) else it })
        }
    }

    private fun saveCare(list: List<CareButton>) {
        prefs.edit().putString(KEY_CARE, CareStore.encode(list)).apply()
        _ui.update { it.copy(careButtons = list) }
    }

    fun fed() = send(JSONObject().put("type", "feed"), "수유를 기록했어요")

    fun findRestStops() = send(JSONObject().put("type", "restRequest").put("nursing", true), "차에서 수유실 휴게소를 찾아요")

    fun addRestStop(index: Int, name: String) = send(JSONObject().put("type", "rest").put("index", index), "$name 을 내비 경유지로 보냈어요")

    fun selectTab(t: Tab) = _ui.update { it.copy(tab = t) }

    fun toast(msg: String) = _ui.update { it.copy(toast = msg) }

    fun toastShown() = _ui.update { it.copy(toast = null) }

    private fun send(msg: JSONObject, okText: String, onOk: () -> Unit = {}) {
        val code = _ui.value.code ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = relay.send(code, msg)
            _ui.update { it.copy(busy = false) }
            when (r.code) {
                200 -> {
                    onOk()
                    toast(okText)
                }
                0 -> toast("중계 서버에 닿지 않아요 · USB 연결을 확인하세요")
                else -> toast("차와 연결이 끊겼어요 · 다시 연결해 주세요")
            }
        }
    }

    companion object {
        private const val KEY_CODE = "car_code"
        private const val KEY_HISTORY = "history"
        private const val KEY_MEMBERS = "members"
        private const val KEY_LUGGAGE = "luggage"
        private const val KEY_SEAT_MEMORY = "seatMemory"
        private const val KEY_RIDES = "rides"
        private const val KEY_BABY_ASKED = "babyAsked"
        private const val KEY_CARE = "careButtons"
        private const val KEY_LAYOUT = "seatLayout"

        /** 아기를 미리 넣어 두는 자리 (인도 쪽 2열, 차의 SeatPlanner 기본 카시트 자리와 같음) */
        private const val BABY_SEAT = "R2_RIGHT"

        /** [차에 적용] 뒤 이 시간 안에 차가 좌석 맞추기를 시작하지 않으면 멈춤 (P단 아님 · 거절) */
        private const val APPLY_START_MS = 15_000L
        private const val MAX_RIDES = 60

        /** 이보다 짧게 달리고 P단이면 여정으로 치지 않음 (주차장 안 이동 등) */
        private const val MIN_RIDE_MS = 60_000L
        private const val MAX_SEAT_MEMORY = 120
        private val RESET_WORDS = Regex("""(다|전부|모두|싹)\s*(지워|지우|빼|없애|취소)|처음부터|초기화|리셋""")
        private const val MAX_HISTORY = 30
        private const val MAX_CHANGES = 20
        private const val POLL_MS = 1_000L
    }
}
