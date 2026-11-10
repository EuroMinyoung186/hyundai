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

enum class Stage { START, CONNECT, MAIN }
enum class Tab { HOME, ROAD }

data class PhoneUi(
    val stage: Stage = Stage.START,
    val tab: Tab = Tab.HOME,
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
    private val _ui = MutableStateFlow(PhoneUi())
    val ui: StateFlow<PhoneUi> = _ui
    private var poll: Job? = null
    private var wasDriving = false

    init {
        prefs.getString(KEY_CODE, null)?.let { code ->
            _ui.update { it.copy(stage = Stage.MAIN, code = code) }
            startPolling()
        }
    }

    // ---- P-1 · P-2 연결 ------------------------------------------------------------------

    fun start() = _ui.update { it.copy(stage = Stage.CONNECT, connectError = null) }

    fun backToStart() = _ui.update { it.copy(stage = Stage.START) }

    fun setCodeInput(s: String) = _ui.update { it.copy(codeInput = s.filter(Char::isDigit).take(6), connectError = null) }

    fun connect() {
        val code = _ui.value.codeInput
        if (code.length != 6 || _ui.value.connecting) return
        _ui.update { it.copy(connecting = true, connectError = null) }
        viewModelScope.launch {
            val r = relay.pair(code)
            when {
                r.code == 0 -> _ui.update { it.copy(connecting = false, connectError = "중계 서버에 닿지 않아요 · 휴대폰 USB 연결과 PC 의 phone 단계를 확인하세요") }
                r.body?.optBoolean("ok") == true -> {
                    prefs.edit().putString(KEY_CODE, code).apply()
                    _ui.update { it.copy(connecting = false, stage = Stage.MAIN, code = code, carOnline = true, tab = Tab.HOME) }
                    startPolling()
                }
                r.body?.optString("error") == "car_offline" ->
                    _ui.update { it.copy(connecting = false, connectError = "차 앱이 잠시 응답하지 않아요 · 몇 초 뒤 다시 눌러 주세요") }
                else -> _ui.update { it.copy(connecting = false, connectError = "차 화면의 코드와 달라요 · 차 화면 오른쪽 위 숫자를 확인하세요") }
            }
        }
    }

    fun disconnect() {
        poll?.cancel()
        prefs.edit().remove(KEY_CODE).apply()
        _ui.value = PhoneUi(stage = Stage.CONNECT)
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
                            if (st != null && st.driving && !wasDriving) tab = Tab.ROAD // 출발하면 P-8 로
                            if (st != null) wasDriving = st.driving
                            u.copy(relayReachable = true, carOnline = body?.optBoolean("car") == true, status = st ?: u.status, tab = tab)
                        }
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    // ---- P-4 여정 --------------------------------------------------------------------------

    fun onHeard(text: String) {
        val r = TripParser.parse(text, _ui.value.draft)
        if (r == null) {
            toast("알아들은 사람이나 짐이 없어요 · 예: 어머니랑 애 둘, 유모차랑 캐리어 하나")
            _ui.update { it.copy(heardText = text, heard = emptySet()) }
        } else {
            _ui.update { it.copy(draft = r.draft, heard = r.heard, heardText = text) }
        }
    }

    fun edit(change: (TripDraft) -> TripDraft) = _ui.update { it.copy(draft = change(it.draft)) }

    fun sendTrip() {
        val d = _ui.value.draft
        val msg = JSONObject()
            .put("type", "trip")
            .put("destination", d.destination)
            .put("seniors", d.seniors).put("adults", d.adults).put("children", d.children).put("infants", d.infants)
            .put("strollers", d.strollers).put("suitcases", d.suitcases).put("babyBags", d.babyBags)
            .put("caregiverNextToBaby", d.caregiverNextToBaby)
        send(msg, "차로 보냈어요") { _ui.update { it.copy(sent = d, prepareSent = false) } }
    }

    fun prepare() = send(JSONObject().put("type", "prepare"), "출발 준비를 보냈어요 · 5초 뒤 좌석이 움직여요") {
        _ui.update { it.copy(prepareSent = true) }
    }

    // ---- P-8 주행 중 -----------------------------------------------------------------------

    fun baby(state: String, label: String) = send(JSONObject().put("type", "baby").put("state", state), "차에 알렸어요: $label")

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
        private const val POLL_MS = 1_000L
    }
}
