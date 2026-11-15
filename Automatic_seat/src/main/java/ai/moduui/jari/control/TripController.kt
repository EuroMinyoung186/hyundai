package ai.moduui.jari.control

import ai.moduui.core.eta.DepartureAdvice
import ai.moduui.core.eta.EtaPrediction
import ai.moduui.core.eta.EtaPredictor
import ai.moduui.core.gleo.GleoCallback
import ai.moduui.core.gleo.GleoCommand
import ai.moduui.core.gleo.GleoContract
import ai.moduui.core.gleo.GleoMode
import ai.moduui.core.gleo.GleoStatus
import ai.moduui.core.model.BabyProfile
import ai.moduui.core.model.BabyState
import ai.moduui.core.model.DrivingState
import ai.moduui.core.model.GeoPoint
import ai.moduui.core.model.RouteSnapshot
import ai.moduui.core.model.SeatId
import ai.moduui.core.model.StateSource
import ai.moduui.core.seat.Occupant
import ai.moduui.core.seat.SeatAxis
import ai.moduui.core.seat.Executor
import ai.moduui.core.seat.SeatAction
import ai.moduui.core.session.SmallAdjust
import ai.moduui.jari.vehicle.MoveDirection
import kotlinx.coroutines.Job
import ai.moduui.core.model.TripRecord
import ai.moduui.core.model.TripRequest
import ai.moduui.core.reststop.RestArea
import ai.moduui.core.route.RoutePath
import ai.moduui.core.reststop.RestStopCandidate
import ai.moduui.core.reststop.RestStopSelector
import ai.moduui.core.seat.SeatPlan
import ai.moduui.core.seat.SeatStep
import ai.moduui.core.session.CarAction
import ai.moduui.core.session.SessionEvent
import ai.moduui.core.session.TripSession
import ai.moduui.jari.car.DrivingSnapshot
import ai.moduui.jari.car.DrivingStateSource
import ai.moduui.jari.data.ProfileRepository
import ai.moduui.jari.navi.NaviEvent
import ai.moduui.jari.navi.NaviGateway
import ai.moduui.jari.navi.NaviState
import ai.moduui.jari.navi.RoadAhead
import ai.moduui.jari.alert.AlertActions
import ai.moduui.jari.alert.AlertButton
import ai.moduui.jari.alert.AlertKind
import ai.moduui.jari.alert.AlertSink
import ai.moduui.jari.alert.CarAlert
import ai.moduui.jari.audio.CabinMusic
import ai.moduui.jari.audio.Song
import ai.moduui.jari.vehicle.VehicleEvent
import ai.moduui.jari.vehicle.VehicleGateway
import ai.moduui.jari.vehicle.VehicleStatus
import ai.moduui.jari.voice.VoiceOutput
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 아기 돌봄 할 일 하나 (휴대폰 type=baby 의 actions[] 와 같은 이름) */
data class CareAct(val kind: String, val value: String? = null)

data class RestStopOffer(
    val candidates: List<RestStopCandidate>,
    val reasonKo: String,
    val nursing: Boolean,
    val createdAt: LocalDateTime,
    val addedName: String? = null,
)

/** 주행 중 소폭 조절 한 건. outcome = null 이면 아직 움직이기 전 (알리는 중) */
data class ComfortAction(
    val seat: SeatId,
    val axis: SeatAxis,
    val labelKo: String,
    val reasonKo: String,
    val outcome: StepOutcome?,
) {
    val applied: Boolean get() = outcome == StepOutcome.DONE || simulated
    val simulated: Boolean get() = outcome == StepOutcome.SIMULATED
}

/** 보호자 휴대폰 연결 상태. code = 차 화면에 보여 주는 6자리 연결 코드 (중계 서버가 없으면 null) */
data class PhoneStatus(val code: String? = null, val connected: Boolean = false, val serverReachable: Boolean = false)

/** 화면이 앞으로 가져와야 할 것 (요청 번호가 바뀔 때만 화면 전환) */
enum class Focus { NONE, BOARDING, DRIVE, REST_STOP, ARRIVE }

/** 출발 알림 단계 (StartAskCard 가 그림) */
sealed interface StartAsk {
    /** Baby Seat 기능을 쓸지. deadlineMs(elapsedRealtime) 가 지나면 아니요 */
    data class Use(val place: String?, val deadlineMs: Long) : StartAsk
    /** 마지막 수유 시각 고르기 */
    data class Pick(val initial: java.time.LocalTime, val intervalMin: Int) : StartAsk

    companion object {
        /** [아니요] 타이머 (초) */
        const val USE_SECONDS = 10
    }
}

data class UiState(
    val now: LocalDateTime = LocalDateTime.now(),
    val simulation: Boolean = false,
    val clockSpeed: Int = 1,
    val vehicle: VehicleStatus = VehicleStatus(),
    val navi: NaviState = NaviState(),
    val driving: DrivingSnapshot = DrivingSnapshot(),
    val voiceEngine: String = "-",
    val profile: BabyProfile = BabyProfile(),
    val lastFeedAt: LocalDateTime? = null,
    val nextFeedAt: LocalDateTime? = null,
    val minutesToFeed: Int? = null,
    /** 출발 알림에서 정한 밥 시간 */
    val mealAt: LocalDateTime? = null,
    val minutesToMeal: Int? = null,
    val trip: TripRequest? = null,
    val plan: SeatPlan? = null,
    val run: RunState? = null,
    val pendingSteps: List<SeatStep> = emptyList(),
    val baby: BabyState = BabyState.AWAKE,
    val babySince: LocalDateTime? = null,
    val babySource: StateSource = StateSource.BUTTON,
    val quiet: Boolean = false,
    val eta: EtaPrediction? = null,
    val departureAdvice: DepartureAdvice? = null,
    val restStops: RestStopOffer? = null,
    val banner: String? = null,
    val focus: Focus = Focus.NONE,
    val focusSeq: Int = 0,
    val arrived: Boolean = false,
    /** 이번 여정에서 출발함 (주행 중이거나 휴게소에 잠시 정차 중) */
    val departed: Boolean = false,
    /** 주행 중 소폭 조절 (조치 칩 · 2-B) */
    val comfort: List<ComfortAction> = emptyList(),
    /** 소폭 조절 전 알리는 중이면 남은 초 */
    val comfortCountdown: Int? = null,
    /** 마지막 음성 안내 문구 (TTS 가 없는 에뮬레이터 시연에서 화면에 보여 줌) */
    val lastVoice: String? = null,
    /** 도착 후 문을 잠갔는데 아기가 카시트에 있음 */
    val rearSeatAlert: Boolean = false,
    /** 여정을 어디서 받았는지 ("Gleo" · "휴대폰" · "화면") */
    val tripSource: String? = null,
    /** 보호자 휴대폰 연결 (P-2 코드 연결) */
    val phone: PhoneStatus = PhoneStatus(),
    /** 카메라가 알린 아기 상태를 [적용] 기다리는 중 (잠듦 · 깸) */
    val careSuggestion: BabyState? = null,
    /** 지금 트는 노래 */
    val music: Song? = null,
    val log: List<String> = emptyList(),
    /** 지도에 그릴 실제 도로 경로 (모르면 빈 목록 → 지점끼리 잇는 개략선) */
    val roadPath: List<GeoPoint> = emptyList(),
    /** 도로 경로 중 지나온 부분 */
    val roadDone: List<GeoPoint> = emptyList(),
    /** 시연 위치의 진행 방향 (도) — 내비 모양 화살표를 돌릴 때 */
    val roadHeading: Float? = null,
) {
    val isDriving: Boolean get() = driving.state == DrivingState.DRIVING || driving.uxRestricted
    val babyMinutes: Long? get() = babySince?.let { Duration.between(it, now).toMinutes() }
}

/**
 * 앱의 중심. 차·내비·Gleo·버튼 이벤트를 core 의 TripSession 에 넣고, 나온 CarAction 을 실제 동작으로 바꿈.
 * 모든 상태 변경은 메인 스레드(scope)에서만 일어나므로 TripSession 을 따로 잠그지 않음.
 */
class TripController(
    private val vehicle: VehicleGateway,
    private val navi: NaviGateway,
    private val driving: DrivingStateSource,
    private val voice: VoiceOutput,
    private val profiles: ProfileRepository,
    private val restAreas: () -> List<RestArea>,
    private val clock: SimClock,
    private val simulation: Boolean,
    private val scope: CoroutineScope,
    private val onTripActive: (Boolean) -> Unit = {},
    /** 상태를 바꾸는 스레드. 앱은 메인, 테스트는 단일 스레드 디스패처. */
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** 내비 위로 뜨는 시스템 알림 (휴게소 제안 · 아기 잠듦/깸) */
    private val alerts: AlertSink = AlertSink.None,
    /** 아기 상태에 맞춰 트는 노래 */
    private val music: CabinMusic = CabinMusic.None,
    /** 출발 · 목적지에 맞는 실제 도로 경로 (시연용 assets/demo_route_*.json, 없으면 null) */
    private val roadPath: (GeoPoint?, GeoPoint?) -> RoutePath? = { _, _ -> null },
    /** 휴게소를 제안하기 직전에 경로 위 휴게소를 찾음 (출발 때 미리 찾지 않음) */
    private val searchRestAreas: suspend () -> Unit = {},
) {
    /** 시연: 도로 경로 위에 놓은 현재 위치 (에뮬레이터 내비 위치가 고정이라 debug demoDrive 가 움직임) */
    private var demoHere: GeoPoint? = null
    private var demoDoneKm: Double = 0.0

    private val session = TripSession(profiles.loadProfile()) { clock.now() }
    private val predictor = EtaPredictor()
    private var history: List<TripRecord> = profiles.loadHistory()
    private var departedAt: LocalDateTime? = null
    private var departedPrediction: EtaPrediction? = null
    private var eta: EtaPrediction? = null
    private var offer: RestStopOffer? = null
    private var banner: String? = null
    private var focus = Focus.NONE
    private var focusSeq = 0
    private val logLines = ArrayDeque<String>()
    private var comfort: List<ComfortAction> = emptyList()
    private var comfortCountdown: Int? = null
    private var comfortJob: Job? = null
    private var lastVoice: String? = null
    private var rearSeatAlert = false
    private var tripSource: String? = null
    private var phone = PhoneStatus()
    /** 카메라가 알린 상태. [적용]을 누르거나 Gleo 에 "적용"이라고 하면 그에 맞게 조치 */
    private var careSuggestion: BabyState? = null

    val runner = SeatStepRunner(vehicle, { session.drivingState }, scope, ::log)

    private val _ui = MutableStateFlow(UiState(simulation = simulation))
    val ui: StateFlow<UiState> = _ui

    fun start() {
        profiles.loadLastFeed()?.let { session.handle(SessionEvent.FeedLogged(it)) } // 저장된 값 복원 (안내는 하지 않음)
        vehicle.start()
        navi.start()
        driving.start()
        // 다른 앱(라디오 등)이 소리를 가져가 노래가 멈추면 내비 위 노래 카드도 내림
        music.setOnStopped { alerts.cancel(AlertKind.MUSIC) }

        scope.launch {
            driving.state.collect { snap -> onDriving(snap) }
        }
        scope.launch {
            var clearAsk: kotlinx.coroutines.Job? = null
            navi.state.map { it.route }.distinctUntilChanged().collect { r ->
                if (r != null) {
                    clearAsk?.cancel()
                    onRoute(r)
                } else {
                    // 경로가 끝나거나 취소되면, 같은 목적지로 다시 길안내를 시작해도 출발 질문을 다시 함 (11/15).
                    // 경유지를 넣어 내비가 다시 찾는 동안에도 경로가 잠깐 비므로, 몇 초 넘게 비어 있을 때만 (경유 때는 다시 안 물음)
                    clearAsk?.cancel()
                    clearAsk = scope.launch {
                        delay(ROUTE_GONE_MS)
                        planAskedFor = null
                    }
                    refreshUi()
                }
            }
        }
        scope.launch {
            navi.state.collect {
                speakRoadAdvice(it)
                refreshUi()
            }
        }
        scope.launch {
            navi.events.collect { e ->
                when (e) {
                    is NaviEvent.DestinationArrived -> onArrived(e.name)
                    is NaviEvent.WaypointArrived -> onWaypointArrived(e.name)
                    NaviEvent.WaypointChanged -> log("경유지 변경됨")
                    NaviEvent.RouteStarted -> log("경로 안내 시작")
                    is NaviEvent.Error -> log("내비 오류: ${e.code}")
                }
                refreshUi()
            }
        }
        scope.launch {
            vehicle.events.collect { e ->
                when (e) {
                    is VehicleEvent.DoorChanged -> {
                        log("${e.seat.labelKo} 문 ${if (e.open) "열림" else "닫힘"}")
                        // 디자인 C-2: 출발 전 문이 열리면 출발 준비 화면을 자동으로 띄움
                        if (e.open && session.plan != null && departedAt == null && !session.arrived && session.drivingState != DrivingState.DRIVING) {
                            requestFocus(Focus.BOARDING)
                        }
                    }
                    is VehicleEvent.OccupancyChanged -> log("${e.seat.labelKo} 착좌 ${e.occupied?.let { if (it) "있음" else "없음" } ?: "모름"}")
                    is VehicleEvent.DoorLockChanged -> onDoorLock(e.locked)
                }
                refreshUi()
            }
        }
        scope.launch { vehicle.status.collect { refreshUi() } }
        scope.launch { runner.state.collect { refreshUi() } }
        // 수유 시간 확인. 실제 시계면 30초마다, 배속 시뮬레이션이면 더 자주.
        scope.launch {
            while (true) {
                delay((30_000L / clock.speed).coerceAtLeast(1_000L))
                if (session.drivingState == DrivingState.DRIVING) apply(session.handle(SessionEvent.Tick))
                refreshUi()
            }
        }
        refreshUi()
    }

    fun stop() {
        runner.cancel()
        driving.stop()
        navi.stop()
        vehicle.stop()
        voice.release()
    }

    // ---- 이벤트 처리 -------------------------------------------------------------------------

    private fun onDriving(snap: DrivingSnapshot) {
        val prev = session.drivingState
        if (snap.state != prev) {
            apply(session.handle(SessionEvent.DrivingChanged(snap.state)))
            if (snap.state == DrivingState.DRIVING && departedAt == null) {
                departedAt = clock.now()
                departedPrediction = eta
                onTripActive(true)
                log("출발")
            }
            if (snap.state == DrivingState.PARKED && session.arrived) onTripActive(false)
        }
        refreshUi()
    }

    /** 출발 알림(수유 · 밥 시간)을 이미 물어본 목적지 */
    private var planAskedFor: String? = null

    private fun onRoute(route: RouteSnapshot) {
        apply(session.handle(SessionEvent.RouteUpdated(route)))
        // 내비에서 길안내를 새 목적지로 시작하면 오른쪽에 출발 알림: 다음 수유 → 밥 시간 (11/14 사용자 요청)
        val key = route.destinationName ?: route.destination?.let { "%.4f,%.4f".format(it.lat, it.lng) }
        // 경유지를 넣어 바뀐 경로(목적지가 경유지로 보일 때 포함)는 새 길안내가 아니므로 다시 묻지 않음
        val viaWaypoint = navi.state.value.waypoints.isNotEmpty() || offer?.addedName != null
        if (viaWaypoint && planAskedFor != null) planAskedFor = key
        if (key != null && key != planAskedFor && !session.arrived && !viaWaypoint) {
            planAskedFor = key
            askUseApp(route.destinationName)
        }
        val now = clock.now()
        eta = predictor.predict(
            departAt = now,
            naviDriveMinutes = (route.remainSeconds / 60).toInt(),
            lastFeedAt = session.lastFeedAt ?: now,
            profile = session.profile,
            history = history,
        )
        refreshUi()
    }

    private fun onArrived(name: String?) {
        log("목적지 도착: ${name ?: ""}")
        stopSong()
        alerts.cancel(AlertKind.REST)
        careSuggestion = null
        alerts.cancel(AlertKind.CARE)
        showArriveAlert(name ?: session.trip?.destinationName)
        val start = departedAt
        val pred = departedPrediction
        if (start != null && pred != null) {
            val actual = Duration.between(start, clock.now()).toMinutes().toInt()
            val record = TripRecord(pred.predictedTotalMinutes, actual, pred.naviDriveMinutes)
            profiles.appendHistory(record)
            history = profiles.loadHistory()
            log("여정 기록: 예측 ${record.predictedMinutes}분 / 실제 ${record.actualMinutes}분")
        }
        departedAt = null
        departedPrediction = null
        apply(session.handle(SessionEvent.DestinationArrived))
    }

    /**
     * 도착 알림을 내비 위 카드로 (11/14 사용자: 앱이 가려져 있어도 화면에 보여야 함).
     * [앱 열기]는 도착 화면(하차 좌석 · 뒷좌석 확인)을 엶.
     */
    private fun showArriveAlert(place: String?) {
        val baby = (session.trip?.infants ?: 0) > 0
        val title = "🏁 ${place?.takeIf { it.isNotBlank() } ?: "목적지"} 도착"
        val text = if (baby) "내리기 전에 뒷좌석 아기를 꼭 확인하세요 · P단이 되면 내리기 좋게 좌석을 맞춰요"
        else "P단이 되면 내리기 좋게 좌석을 맞춰요"
        alerts.show(
            CarAlert(AlertKind.ARRIVE, title, text, listOf(AlertButton(AlertActions.ARRIVE_OPEN, "앱 열기"), AlertButton(AlertActions.ARRIVE_OK, "확인"))),
        )
    }

    /** 노래 틀기 + 내비 위에 "🎵 ○○ 트는 중 [끄기]" 작은 카드 (앱이 가려져 있어도 무엇이 나오는지 보이게) */
    private fun playSong(song: Song) {
        music.play(song)
        if (music.playing != song) {
            alerts.cancel(AlertKind.MUSIC)
            return
        }
        val text = if (session.quietMode) "안내 음성 작게 · Baby Seat" else "Baby Seat"
        alerts.show(CarAlert(AlertKind.MUSIC, "🎵 ${song.labelKo} 트는 중", text, listOf(AlertButton(AlertActions.MUSIC_OFF, "끄기"))))
    }

    private fun stopSong() {
        music.stop()
        alerts.cancel(AlertKind.MUSIC)
    }

    private fun apply(actions: List<CarAction>) {
        for (a in actions) {
            when (a) {
                is CarAction.Speak -> say(a.textKo, a.quiet)
                is CarAction.AdjustWhileDriving -> startComfort(a.adjusts, a.reasonKo)
                is CarAction.Banner -> banner = a.textKo
                is CarAction.ShowPlan -> requestFocus(Focus.BOARDING)
                is CarAction.ShowBabyState -> log("아기 상태: ${a.state.labelKo} (${a.source.labelKo})")
                is CarAction.SetQuietMode -> {
                    log(if (a.on) "조용한 안내 켬" else "조용한 안내 끔")
                    // 잠들면 수유 휴게소 제안을 미룸 (깨면 세션이 다시 제안)
                    if (a.on && offer?.nursing == true) offer = null
                }
                is CarAction.SuggestNursingStops -> scope.launch { offerRestStops(nursing = true, minutesUntilFeeding = a.minutesUntilFeeding) }
                CarAction.SuggestNearestStop -> scope.launch { offerRestStops(nursing = false, minutesUntilFeeding = 0) }
                is CarAction.SuggestMealStops -> scope.launch {
                    offerRestStops(nursing = false, minutesUntilFeeding = a.minutesUntilMeal, reasonKo = "밥 ${if (a.minutesUntilMeal > 0) "${a.minutesUntilMeal}분 전" else "시간"} · 휴게소")
                }
                is CarAction.ExecuteSeatSteps -> if (holding) {
                    // 누르고 있는 동안만: 사람이 버튼을 누르고 있으므로 카운트다운 없이 바로, 다시 누르면 이어서
                    val resume = runner.state.value?.reasonKo == a.reasonKo
                    runner.run(withManual(a.steps), a.reasonKo, countdownSeconds = 0, keepUndo = resume)
                } else {
                    runner.run(withManual(a.steps), a.reasonKo, a.countdownSeconds)
                }
                is CarAction.QueueSeatSteps -> log("정차 후 실행 예약: ${a.reasonKo} ${a.steps.size}단계")
                CarAction.RearSeatReminder -> {
                    say("도착했어요. 내리기 전에 뒷좌석 아기를 꼭 확인하세요", session.quietMode)
                    banner = "뒷좌석 아기를 확인하세요"
                    requestFocus(Focus.ARRIVE)
                }
            }
        }
        refreshUi()
    }

    /** 이미 말한 앞길 안내 (같은 과속방지턱을 다가가는 동안 거듭 말하지 않게). 지나가서 안내가 없어지면 비움 */
    private var spokenRoadKey: String? = null

    /**
     * 아기가 자는데 과속방지턱 · 급회전이 가까우면 한 번만 작게 말함 (화면을 안 봐도 되게).
     * 규칙은 RoadAhead. 속도는 바꾸지 않고 운전자에게 알리기만 함.
     */
    private fun speakRoadAdvice(state: NaviState) {
        if (session.drivingState != DrivingState.DRIVING) return
        val a = RoadAhead.advice(state, session.babyState == BabyState.ASLEEP, session.profile.nickname)
        val key = a?.takeIf { it.urgent }?.key
        if (key != null && key != spokenRoadKey) say(a.voice, quiet = true)
        spokenRoadKey = key
    }

    /** 내비 경유지 도착. 넣어 둔 수유 휴게소면 수유 기록을 부탁함 */
    private fun onWaypointArrived(name: String?) {
        log("경유지 도착: ${name ?: ""}")
        val o = offer
        val stop = o?.addedName
        if (o != null && o.nursing && stop != null && (name == null || name.contains(stop) || stop.contains(name))) {
            banner = "🍼 $stop 도착 · 수유가 끝나면 [수유했어요]를 눌러 주세요"
            say("$stop 에 도착했어요. 수유가 끝나면 수유했어요를 눌러 주세요", session.quietMode)
        }
    }

    private fun requestFocus(f: Focus) {
        focus = f
        focusSeq++
    }

    private fun say(text: String, quiet: Boolean) {
        voice.speak(text, quiet)
        lastVoice = text
        log("안내: $text")
    }

    /** 주행 중 소폭 조절: 먼저 알리고(취소 가능) 잠시 뒤 짧게 움직임 */
    private fun startComfort(adjusts: List<SmallAdjust>, reasonKo: String) {
        comfortJob?.cancel()
        comfort = comfort.filter { it.outcome != null } + adjusts.map { ComfortAction(it.seat, it.axis, it.labelKo, reasonKo, null) }
        comfortJob = scope.launch {
            for (s in COMFORT_ANNOUNCE_SECONDS downTo 1) {
                comfortCountdown = s
                refreshUi()
                delay(1_000)
            }
            comfortCountdown = null
            for (a in adjusts) {
                val outcome = runner.smallAdjust(a.seat, a.axis, a.towardPositive, a.maxPercent)
                comfort = comfort.map { if (it.seat == a.seat && it.axis == a.axis && it.outcome == null) it.copy(outcome = outcome) else it }
                log("${a.labelKo}: ${outcome.labelKo}")
                refreshUi()
            }
        }
    }

    /** 조치 칩의 [되돌리기]: 움직이기 전이면 취소, 움직였으면 반대로. 안내 음성도 원래대로. */
    fun undoComfort() {
        comfortJob?.cancel()
        comfortJob = null
        comfortCountdown = null
        comfort = emptyList()
        session.clearQuiet()
        scope.launch {
            runner.undoSmall()
            log("조치를 되돌렸어요")
            refreshUi()
        }
        refreshUi()
    }

    /** 디자인 C-5 [옮기기]: 동승 보호자 자리를 아기 쪽으로 조금 */
    fun moveCaregiverTowardBaby() {
        val seat = session.plan?.assignments?.entries?.firstOrNull { it.value == Occupant.CAREGIVER }?.key ?: return
        say("보호자 자리를 아기 쪽으로 조금 옮길게요", session.quietMode)
        startComfort(
            listOf(SmallAdjust(seat, SeatAxis.FORE_AFT, towardPositive = false, "보호자 자리 아기 쪽으로 조금", TripSession.CAREGIVER_ADJUST_PERCENT)),
            "아기가 울어요",
        )
    }

    /**
     * 좌석 하나를 정한 위치로 (차 3D 화면에서 고르거나 휴대폰 seatAdjust).
     * 값은 0..100 (앞뒤 100 = 맨 앞, 등받이 100 = 가장 세움). null 인 축은 그대로.
     * 움직임은 SeatStepRunner 만: 5초 카운트다운 · [멈추기] · P단만 · 앉은 좌석 건너뜀 · 문 열리면 정지 · [되돌리기].
     */
    fun adjustSeat(seat: SeatId, foreAft: Int?, backrest: Int?, from: String) {
        if (runner.isRunning) {
            banner = "좌석을 맞추는 중이에요 · 끝난 뒤 다시 해 주세요"
            refreshUi()
            return
        }
        // 직접 맞춘 값은 사용자가 원한 것 → 이후 여정 [적용] 때 같은 좌석 · 축의 규칙 값 대신 씀 (11/15 사용자 결정)
        foreAft?.let { manual[seat to SeatAxis.FORE_AFT] = it.coerceIn(0, 100) }
        backrest?.let { manual[seat to SeatAxis.BACKREST] = it.coerceIn(0, 100) }
        val reason = "$from 에서 직접 맞춤"
        val steps = listOfNotNull(
            foreAft?.let { SeatStep(seat, SeatAction.Move(SeatAxis.FORE_AFT, it.coerceIn(0, 100) / 100f), Executor.SDK_CONTROL, reason) },
            backrest?.let { SeatStep(seat, SeatAction.Move(SeatAxis.BACKREST, it.coerceIn(0, 100) / 100f), Executor.SDK_CONTROL, reason) },
        )
        if (steps.isEmpty()) return
        log("${seat.labelKo} 직접 맞춤 요청 ($from): 앞뒤 ${foreAft ?: "-"} · 등받이 ${backrest ?: "-"}")
        runner.run(steps, "${seat.labelKo} 직접 맞춤 · $from")
        refreshUi()
    }

    /** 직접 맞춘 좌석 · 축 → 값(0..100). 새 여정을 받으면 지움 (가장 최근에 보낸 것이 기준) */
    private val manual = mutableMapOf<Pair<SeatId, SeatAxis>, Int>()

    /**
     * 여정 단계에 직접 맞춘 값을 씌움. 대회 · 안전 요건은 그대로: 주행 중 한계는 휴대폰 · 차 화면이 이미 지키고,
     * 움직임은 SeatStepRunner(P단 · 카운트다운 · 앉은 좌석 · 문)만 거침. 운전석은 원래 단계가 없음.
     */
    private fun withManual(steps: List<SeatStep>): List<SeatStep> = steps.map { st ->
        val mv = st.action as? SeatAction.Move ?: return@map st
        val v = manual[st.seat to mv.axis] ?: return@map st
        // 뒤보기 카시트 앞좌석 공간 확보는 그보다 뒤로 가지 않음
        val floor = if (mv.axis == SeatAxis.FORE_AFT && "후방 카시트" in st.reasonKo) (mv.targetFraction * 100).toInt() else 0
        st.copy(action = SeatAction.Move(mv.axis, v.coerceAtLeast(floor) / 100f), reasonKo = "${st.reasonKo} · 직접 맞춘 값")
    }

    /** 도착 화면 [원래대로]: 소폭 조절과 하차 준비를 모두 되돌림 */
    fun restoreSeats() {
        comfortJob?.cancel()
        comfortCountdown = null
        comfort = emptyList()
        session.seatsRestored()
        scope.launch {
            runner.undoSmall()
            runner.undoLast()
            refreshUi()
        }
        refreshUi()
    }

    private fun onDoorLock(locked: Boolean) {
        if (!locked) {
            rearSeatAlert = false
            return
        }
        // 기획: 문을 잠글 때 카시트에 아기가 감지되면 경고. 아기 상태를 아는 동안은 아기가 있는 것으로 봄.
        if (session.arrived && (session.trip?.infants ?: 0) > 0 && session.babyState != BabyState.UNKNOWN) {
            rearSeatAlert = true
            say("문을 잠갔어요. 카시트에 아기가 있어요. 꼭 확인하세요", quiet = false)
            alerts.show(
                CarAlert(AlertKind.ARRIVE, "🚨 카시트에 아기가 있어요", "문을 잠갔어요 · 뒷좌석을 꼭 확인하세요", listOf(AlertButton(AlertActions.ARRIVE_OK, "확인했어요"))),
            )
            requestFocus(Focus.ARRIVE)
        }
    }

    private suspend fun offerRestStops(nursing: Boolean, minutesUntilFeeding: Int, reasonKo: String? = null): RestStopOffer? {
        val route = navi.state.value.route
        val here = demoHere ?: navi.currentLocation()
        if (route == null || here == null) {
            banner = "경로나 현재 위치를 몰라 휴게소를 찾지 못했어요"
            refreshUi()
            return null
        }
        // 경로 위 휴게소는 지금(수유 · 밥 30분 전 등 제안할 때) 찾음
        searchRestAreas()
        val candidates = RestStopSelector.select(
            current = here,
            destination = route.destination,
            remainSeconds = route.remainSeconds,
            minutesUntilFeeding = minutesUntilFeeding,
            // 이미 경유지로 넣은 휴게소는 다시 권하지 않음
            restAreas = restAreas().filterNot { r -> navi.state.value.waypoints.any { w -> w.contains(r.name) || r.name.contains(w) } },
            requireNursingRoom = nursing,
        )
        val reason = reasonKo
            ?: if (nursing) "수유 ${if (minutesUntilFeeding > 0) "${minutesUntilFeeding}분 전" else "시간"} · 수유실 있는 휴게소" else "아기가 울어요 · 가까운 휴게소"
        val o = RestStopOffer(candidates, reason, nursing, clock.now())
        offer = o
        requestFocus(Focus.REST_STOP)
        showRestAlert(o)
        if (candidates.isEmpty()) {
            say("경로 근처에 ${if (nursing) "수유실 있는 " else ""}휴게소가 없어요", session.quietMode)
        } else {
            say("${candidates[0].restArea.name}까지 약 ${candidates[0].etaMinutes}분이에요", session.quietMode)
        }
        refreshUi()
        return o
    }

    /** 휴게소 제안을 내비 위 알림으로: 후보 2곳 + [괜찮아요] */
    private fun showRestAlert(o: RestStopOffer) {
        val top = o.candidates.take(2)
        if (top.isEmpty()) {
            // 앞쪽에 들를 휴게소가 없어도 알림은 띄움 (오른쪽 패널은 후보가 있을 때만 떠서 아무것도 안 보였음, 11/15)
            alerts.show(
                CarAlert(
                    AlertKind.PLAN, "${if (o.nursing) "🍼" else "🧷"} ${o.reasonKo}", "경로 앞쪽에서 들를 휴게소를 못 찾았어요 · 내비에서 찾아 볼까요?",
                    listOf(AlertButton(AlertActions.REST_SEARCH, "내비에서 찾기"), AlertButton(AlertActions.REST_DISMISS, "괜찮아요")),
                ),
            )
            return
        }
        val text = if (top.isEmpty()) "경로 근처에서 찾지 못했어요"
        else top.mapIndexed { i, c ->
            "${i + 1}. ${c.restArea.name}${if (c.restArea.hasNursingRoom) " · 수유실" else ""} · ${"%.0f".format(c.distanceKm)}km · 약 ${c.etaMinutes}분"
        }.joinToString("\n")
        val buttons = top.mapIndexed { i, c -> AlertButton(AlertActions.rest(i), "${i + 1}번 경유") } +
            AlertButton(AlertActions.REST_DISMISS, "괜찮아요")
        val icon = when {
            o.nursing -> "🍼"
            o.reasonKo.startsWith("밥") -> "🍚"
            else -> "🧷"
        }
        alerts.show(CarAlert(AlertKind.REST, "$icon ${o.reasonKo}", text, buttons))
    }

    // ---- 화면·Gleo 에서 부르는 동작 ------------------------------------------------------------

    /** source = null 이면 화면에서 고친 것: 처음 받은 곳(휴대폰 · Gleo)을 그대로 둠 */
    fun planTrip(trip: TripRequest, source: String? = null) {
        tripSource = source ?: tripSource ?: "Gleo"
        if (source != null) manual.clear() // 새로 보낸 여정이 가장 최근 값
        if (source == "휴대폰") log("휴대폰에서 여정 받음: ${trip.destinationName ?: ""}")
        offer = null // 지난 여정의 휴게소 제안이 남지 않게
        comfortJob?.cancel()
        comfortCountdown = null
        comfort = emptyList()
        rearSeatAlert = false
        lastVoice = null
        banner = null // 지난 도착의 "뒷좌석 아기를 확인하세요" 같은 알림이 새 여정에 남지 않게
        alerts.cancel(AlertKind.ARRIVE)
        apply(session.handle(SessionEvent.PlanRequested(trip)))
    }

    fun confirmDeparture() {
        apply(session.handle(SessionEvent.DepartureConfirmed))
        onTripActive(true)
    }

    private var holding = false

    /**
     * 세팅 화면 [적용 · 누르면 맞춰요]: 한 번 누르면 맞춤 (11/15 사용자 결정, 예전에는 누르고 있는 동안만).
     * 손을 떼도 멈추지 않으므로 누르고 있기 모드(카운트다운 없음)를 쓰지 않고, 5초 카운트다운 · [멈추기]를 그대로 거침.
     */
    fun holdApplyStart() {
        apply(session.handle(SessionEvent.DepartureConfirmed))
        onTripActive(true)
    }

    /** 손을 떼면 바로 멈춤 (움직이던 좌석 STOP) */
    fun holdApplyStop() {
        if (runner.isRunning) runner.cancel()
    }

    /** 기본 내비에 경로 안내 요청 (Gleo · 화면 · 시뮬레이터) */
    fun requestNaviRoute(name: String, to: GeoPoint) {
        val ok = navi.requestRoute(name, to)
        log(if (ok) "내비 경로 요청: $name" else "내비 경로 요청 실패: $name")
        if (!ok) banner = "내비에 목적지를 보내지 못했어요"
        refreshUi()
    }

    /** 목적지 고르기: 내비 즐겨찾기 · 최근 목적지 다시 받기 */
    fun loadNaviPlaces() = navi.loadPlaces()

    /** 내비 장소 검색 화면을 검색어와 함께 엶. 고른 곳은 내비 목적지 콜백으로 앱에 들어옴 */
    fun searchNaviPlace(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        if (!navi.searchPlace(k)) {
            banner = "내비 검색을 열지 못했어요 · 아래 목록에서 골라 주세요"
            refreshUi()
        }
    }

    /**
     * 외부 시뮬레이터(tools/sim) 전용: 에뮬레이터 내비는 위치가 고정이라 실제 도착 이벤트가 오지 않음.
     * 경로를 끝내고 도착 처리. 실제 도착은 내비의 onDestinationArrived 로만 들어옴.
     */
    fun simulateArrival(name: String?) {
        navi.cancelRoute()
        onArrived(name)
    }

    /** 카시트 좌석 허용 폭만 바꿈. 좌석 배치와 무관해서 다시 계획하지 않음. */
    fun setCarSeatAllowance(percent: Int) {
        val p = session.profile.copy(carSeatReclineAllowance = percent.coerceIn(0, 10))
        session.updateProfile(p)
        profiles.saveProfile(p)
        refreshUi()
    }

    /** 외부 시뮬레이터 전용: 에뮬레이터 내비는 남은 시간이 줄지 않으므로 "도착 N분 전" 상황을 만듦 */
    fun simulateRemaining(minutes: Int) {
        navi.state.value.route?.let { onRoute(it.copy(remainSeconds = minutes * 60L)) }
    }

    /** 내비 화면을 앞으로 (시연: 이벤트 순간을 내비에서 보여 줄 때) */
    fun showNavi(): Boolean = navi.bringToFront()

    /** 시연: 앱 시계 빠르기 (주행 시연 배속과 맞춰 남은 시간 · 도착 시각 · 수유까지 남은 시간이 같이 흐르게) */
    fun setTimeSpeed(x: Int) {
        if (clock.speed == x) return
        clock.setSpeed(x)
        refreshUi()
    }

    /** 도로 경로를 새로 받았을 때 (debug) */
    fun roadPathsChanged() {
        pathFor = null
        refreshUi()
    }

    /** 시연을 처음부터 (debug 리셋 버튼): 내비 경로 취소 · 제안/알림 지움 · 도착 상태 지움 · 아기 깸 · 수유 1시간 전 */
    fun resetDemo() {
        askJob?.cancel()
        askJob = null
        askingUse = false
        _ask.value = null
        apply(session.handle(SessionEvent.AssistSet(true)))
        demoHere = null
        demoDoneKm = 0.0
        pathFor = null
        pathCache = null
        offer = null
        banner = null
        planAskedFor = null
        careSuggestion = null
        alerts.cancel(AlertKind.REST)
        alerts.cancel(AlertKind.PLAN)
        alerts.cancel(AlertKind.ARRIVE)
        alerts.cancel(AlertKind.CARE)
        stopSong()
        // 경유지가 있으면 내비가 취소를 거부하고 멈춰 버림(11/15 확인) → 경유지를 먼저 빼고 잠시 뒤 취소
        // 경유지가 없을 때 경유지 빼기를 보내면 내비가 몇 초 뒤 경로를 다시 만들어 버림(11/15 확인) → 있을 때만
        val hadWaypoint = navi.state.value.waypoints.isNotEmpty()
        if (hadWaypoint) navi.removeWaypoints()
        scope.launch {
            delay(if (hadWaypoint) 4_000 else 300)
            // 취소가 안 먹으면 (내비가 바쁨) 몇 번 더
            for (i in 0 until 4) {
                navi.cancelRoute()
                delay(2_500)
                if (navi.state.value.route == null && !navi.state.value.guiding) break
            }
            planAskedFor = null
            askJob?.cancel()
            askingUse = false
            _ask.value = null
            alerts.cancel(AlertKind.PLAN)
            refreshUi()
        }
        session.clearArrival()
        apply(session.handle(SessionEvent.BabyReported(BabyState.AWAKE, StateSource.BUTTON)))
        logFeed(clock.now().minusMinutes(60))
        refreshUi()
    }

    /** 외부 시뮬레이터 전용: 경유지 도착 (에뮬레이터 내비는 위치가 고정이라 도착 이벤트가 오지 않음) */
    fun simulateWaypointArrived(name: String?) {
        onWaypointArrived(name)
        refreshUi()
    }

    /** 지금 경로에 맞는 실제 도로 경로 (시연용, 없으면 null) */
    fun currentRoadPath(): RoutePath? {
        // 경유지를 넣는 동안 내비 경로가 잠깐 비어도 시연 주행 중이면 지금 도로를 계속 씀
        val dest = navi.state.value.route?.destination ?: return if (demoHere != null) pathCache else null
        // 아직 도로를 못 찾았으면 (debug 가 받아 오는 중) 다시 찾아 봄
        if (dest != pathFor || pathCache == null) {
            pathFor = dest
            val prev = pathCache
            // 경유지를 넣으면 내비가 다음 목적지를 경유지로 알려 줌 → 그곳이 지금 도로 위(3km 안)면 같은 도로를 계속 씀
            pathCache = roadPath(navi.state.value.current, dest)
                ?: prev?.takeIf { it.locate(dest).second <= 3.0 || demoHere != null }
        }
        return pathCache
    }
    private var pathFor: GeoPoint? = null
    private var pathCache: RoutePath? = null

    /**
     * 외부 시뮬레이터 전용 (debug demoDrive): 도로 경로를 따라 [doneKm] 간 곳에 차를 놓고 남은 시간을 줄임.
     * null 이면 시연 위치를 지움. 좌석 · 안전 장치와는 무관하고 지도 · 남은 시간 · 휴게소 고르기에만 쓰임.
     */
    fun simulatePosition(doneKm: Double?, remainSeconds: Long? = null) {
        val path = currentRoadPath()
        if (doneKm == null || path == null) {
            demoHere = null
            demoDoneKm = 0.0
        } else {
            demoDoneKm = doneKm.coerceIn(0.0, path.lengthKm)
            demoHere = path.pointAt(demoDoneKm)
        }
        val route = navi.state.value.route
        if (remainSeconds != null && route != null) onRoute(route.copy(remainSeconds = remainSeconds)) else refreshUi()
    }

    /** 외부 시뮬레이터 전용: 다시 시작할 때 내비 경로 정리 */
    fun cancelNaviRoute() {
        navi.cancelRoute()
        refreshUi()
    }

    fun nudgeSeat(seat: SeatId, axis: SeatAxis, dir: MoveDirection) = runner.nudge(seat, axis, dir)

    fun toggleSeatHeat(seat: SeatId) = runner.toggleHeat(seat)

    fun reportBaby(state: BabyState, source: StateSource) {
        // 카메라(아기 모니터)는 선택 기능: 설정에서 켠 경우에만 쓰고, 잠듦 · 깸은 알림으로 먼저 물어봄
        if (source == StateSource.BABY_MONITOR) {
            onCameraReport(state)
            return
        }
        careSuggestion = null
        alerts.cancel(AlertKind.CARE)
        apply(session.handle(SessionEvent.BabyReported(state, source)))
        if (state == BabyState.CRYING || state == BabyState.HUNGRY) stopSong()
    }

    /**
     * 차에 연결된 카메라가 본 아기 상태. 잠듦 · 깸이 바뀌면 "아이가 잠들었어요 [적용]" 알림.
     * 울음 · 배고픔은 바로 휴게소를 찾아야 하므로 묻지 않고 반영.
     */
    private fun onCameraReport(state: BabyState) {
        if (!session.profile.cameraWatch) {
            log("카메라 사용이 꺼져 있어 카메라 상태를 쓰지 않음 (${state.labelKo})")
            return
        }
        // 카메라는 주행 중(D단)에만 씀 (11/15 사용자 결정). 정차 · 주차 중 판정은 버림
        if (!cameraOn()) {
            log("주행 중이 아니라 카메라 상태를 쓰지 않음 (${state.labelKo})")
            return
        }
        when (state) {
            BabyState.ASLEEP, BabyState.AWAKE -> {
                if (state == session.babyState || state == careSuggestion) return
                careSuggestion = state
                val name = RoadAhead.subjectKo(session.profile.nickname.ifBlank { "아기" })
                val title = if (state == BabyState.ASLEEP) "$name 잠들었어요" else "$name 깼어요"
                val text = if (state == BabyState.ASLEEP) "적용: 음성 작게 · 자장가" else "적용: 음성 원래대로 · 동요"
                log("카메라: ${state.labelKo} → 적용을 기다림")
                alerts.show(
                    CarAlert(
                        AlertKind.CARE, "📷 $title", "$text",
                        listOf(AlertButton(AlertActions.CARE_APPLY, "적용"), AlertButton(AlertActions.CARE_DISMISS, "괜찮아요")),
                    ),
                )
                // 자는 아기를 깨우지 않게 잠듦은 화면 알림만, 깸은 짧게 말함
                if (state == BabyState.AWAKE) say("$title. 적용할까요?", quiet = false)
                refreshUi()
            }
            else -> {
                careSuggestion = null
                alerts.cancel(AlertKind.CARE)
                apply(session.handle(SessionEvent.BabyReported(state, StateSource.BABY_MONITOR)))
            }
        }
    }

    /** 카메라를 켤 때: 주행 중이거나 D단 */
    fun cameraOn(): Boolean = _ui.value.isDriving || _ui.value.driving.gear == "D"

    /** 카메라 알림의 [적용] (버튼 · Gleo "적용"). 적용할 것이 없으면 false */
    fun applyCare(): Boolean {
        val state = careSuggestion ?: return false
        careSuggestion = null
        alerts.cancel(AlertKind.CARE)
        apply(session.handle(SessionEvent.BabyReported(state, StateSource.BABY_MONITOR)))
        val acts = if (state == BabyState.ASLEEP) listOf(CareAct("NAVI_QUIET"), CareAct("MUSIC", "자장가"))
        else listOf(CareAct("NAVI_NORMAL"), CareAct("MUSIC", "동요"))
        scope.launch {
            val done = runCareActions(acts)
            banner = "적용됨 · ${done.joinToString(" · ")}"
            log(banner ?: "")
            refreshUi()
        }
        return true
    }

    /**
     * 휴대폰 "아기 상태 알리기" (type=baby 확장, 11/14).
     * state 가 있으면 아기 상태로 반영하고, 보호자가 고른 할 일(actions)을 차에서 실행한 뒤 내비 위 알림으로 알려 줌.
     */
    fun onPhoneBaby(state: BabyState?, label: String?, emoji: String?, acts: List<CareAct>) {
        if (state != null) reportBaby(state, StateSource.PHONE)
        if (label.isNullOrBlank() && acts.isEmpty()) return
        val title = listOfNotNull(emoji?.takeIf { it.isNotBlank() }, label?.takeIf { it.isNotBlank() } ?: state?.labelKo).joinToString(" ")
        log("휴대폰: $title ${acts.joinToString { it.kind }}")
        scope.launch {
            val done = runCareActions(acts)
            val text = done.joinToString(" · ").ifEmpty { "휴대폰에서 알렸어요" }
            alerts.show(CarAlert(AlertKind.CARE, "📱 $title", text, listOf(AlertButton(AlertActions.CARE_DISMISS, "확인"))))
            banner = "📱 $title · $text"
            refreshUi()
        }
    }

    /**
     * 아기 돌봄 할 일 실행. 반환은 화면 · 알림에 보여 줄 짧은 글.
     * NAVI_QUIET/NAVI_NORMAL: 앱 안내 음성 크기 (내비 자체 음량을 바꾸는 공개 API 는 없음)
     * MUSIC(value)/MUSIC_OFF, TEMP_UP/TEMP_DOWN(실내 1도, Pleos HVAC), NEARBY(value = 찾을 곳)
     */
    private suspend fun runCareActions(acts: List<CareAct>): List<String> = buildList {
        for (a in acts.take(3)) {
            when (a.kind) {
                "NAVI_QUIET" -> {
                    session.quietOn()
                    add("안내 음성 작게")
                }
                "NAVI_NORMAL" -> {
                    session.clearQuiet()
                    add("안내 음성 원래대로")
                }
                "MUSIC" -> {
                    val v = a.value.orEmpty().lowercase()
                    val song = if (listOf("자장", "잔잔", "백색", "수면", "lullaby", "sleep", "calm").any { it in v }) Song.LULLABY else Song.KIDS
                    playSong(song)
                    add(song.labelKo)
                }
                "MUSIC_OFF" -> {
                    stopSong()
                    add("노래 끔")
                }
                "TEMP_UP", "TEMP_DOWN" -> {
                    val t = vehicle.nudgeCabinTemperature(if (a.kind == "TEMP_UP") 1f else -1f)
                    add(t?.let { "실내 ${"%.0f".format(it)}°C로 ${if (a.kind == "TEMP_UP") "올림" else "내림"}" } ?: "실내 온도는 바꾸지 못했어요")
                }
                "NEARBY" -> {
                    val what = a.value?.takeIf { it.isNotBlank() } ?: "쉴 곳"
                    val nursing = "수유" in what
                    val restLike = nursing || listOf("기저귀", "화장실", "정차", "휴게", "쉴").any { it in what }
                    when {
                        restLike -> {
                            val o = if (navi.state.value.route != null) {
                                offerRestStops(nursing = nursing, minutesUntilFeeding = 0, reasonKo = "$what · 가까운 휴게소${if (nursing) "" else " · 정차할 곳"}")
                            } else null
                            when {
                                o != null && o.candidates.isNotEmpty() -> add("$what ${o.candidates.size}곳 찾음 · 위 알림에서 골라 주세요")
                                // 경로를 모르면 (안내 전 · 앱이 가려져 있던 동안) 내비의 주변 검색으로
                                navi.searchPlace(if (nursing) "수유실" else "휴게소") -> add("내비에서 가까운 ${if (nursing) "수유실" else "휴게소"} 검색")
                                else -> add("찾지 못했어요 · $what")
                            }
                        }
                        // 소아과 · 약국처럼 휴게소에 없는 곳은 내비 검색으로
                        navi.searchPlace(what) -> add("내비에서 '$what' 검색")
                        else -> add("찾지 못했어요 · $what")
                    }
                }
                else -> log("모르는 할 일: ${a.kind}")
            }
        }
    }

    fun dismissCare() {
        careSuggestion = null
        alerts.cancel(AlertKind.CARE)
        refreshUi()
    }

    fun stopMusic() {
        stopSong()
        refreshUi()
    }

    /**
     * 출발 알림 1: 다음 수유 시각. 주행 중 화면 규칙대로 한 번에 한 가지만 묻고, 고르면 2(밥 시간)로 넘어감.
     * 지금 계산(마지막 수유 + 간격)이 있으면 [그대로], 없으면 [모름].
     */
    private fun askFeedTime(place: String?) {
        val now = clock.now()
        val next = session.nextFeedDue()
        val last = session.lastFeedAt
        val text = buildString {
            place?.takeIf { it.isNotBlank() }?.let { append(it).append(" 가는 길 · ") }
            append(
                when {
                    next != null && last != null -> "마지막 수유 ${last.format(HM)} · 이대로면 ${next.format(HM)}쯤"
                    next != null -> "지금 정해 둔 시간 ${next.format(HM)}"
                    else -> "마지막 수유 기록이 없어요"
                },
            )
            append("\n정하면 30분 전에 수유실 휴게소를 찾아 드려요")
        }
        val keep = if (next != null && next.isAfter(now)) "그대로" else "모름"
        alerts.show(
            CarAlert(
                AlertKind.PLAN, "🍼 다음 수유는 언제쯤이에요?", text,
                listOf(
                    AlertButton(AlertActions.feedIn(30), "30분 뒤"),
                    AlertButton(AlertActions.feedIn(60), "1시간 뒤"),
                    AlertButton(AlertActions.feedIn(120), "2시간 뒤"),
                    AlertButton(AlertActions.FEED_KEEP, keep),
                ),
            ),
        )
        log("출발 알림: 수유 시간 물음")
    }

    // ───── 출발 알림 (11/15): Baby Seat 기능 사용? [예]/[아니요 10초] → 마지막 수유 시각 고르기 ─────

    private var askJob: kotlinx.coroutines.Job? = null
    /** [예] 를 기다리는 중 (Gleo "예/적용"도 받음) */
    private var askingUse = false
    private val _ask = MutableStateFlow<StartAsk?>(null)
    val ask: StateFlow<StartAsk?> = _ask

    private fun askUseApp(place: String?) {
        askJob?.cancel()
        askingUse = true
        alerts.cancel(AlertKind.PLAN)
        // 답하고 수유 시각까지 정할 때까지는 휴게소 제안을 하지 않음 (질문 카드와 휴게소 패널이 같이 뜨지 않게)
        apply(session.handle(SessionEvent.AssistSet(false)))
        _ask.value = StartAsk.Use(place?.takeIf { it.isNotBlank() }, android.os.SystemClock.elapsedRealtime() + StartAsk.USE_SECONDS * 1000L)
        say("Baby Seat 기능을 사용할까요? 예 라고 말하거나 눌러 주세요", session.quietMode)
        askJob = scope.launch {
            delay(StartAsk.USE_SECONDS * 1000L)
            answerUse(false)
        }
        log("출발 알림: 기능 사용 물음")
    }

    /** 출발 알림 답. 예 → 마지막 수유 시각 고르기, 아니요 → 이번 여정은 휴게소 제안 안 함 */
    fun answerUse(yes: Boolean) {
        if (!askingUse) return
        askingUse = false
        askJob?.cancel()
        askJob = null
        // 예: 수유 시각을 정한 뒤 켬 (confirm/cancelFeedPick)
        if (!yes) apply(session.handle(SessionEvent.AssistSet(false)))
        if (yes) {
            // 12시간 넘은 기록(어제 시험 등)은 기본값으로 쓰지 않음 → 1시간 전
            val recent = session.lastFeedAt?.takeIf { java.time.Duration.between(it, clock.now()).toHours() < 12 }
            val t = (recent ?: clock.now().minusHours(1)).toLocalTime()
            _ask.value = StartAsk.Pick(java.time.LocalTime.of(t.hour, t.minute / 5 * 5), session.profile.feedIntervalMin)
        } else {
            _ask.value = null
            say("알겠어요. 이번에는 휴게소 제안을 하지 않을게요", session.quietMode)
        }
        log(if (yes) "기능 사용: 예" else "기능 사용: 아니요")
        refreshUi()
    }

    /** 마지막 수유 시각 [확인]. 지금보다 뒤 시각이면 어제로 봄 */
    fun confirmFeedPick(t: java.time.LocalTime) {
        val now = clock.now()
        var at = now.toLocalDate().atTime(t)
        if (at.isAfter(now)) at = at.minusDays(1)
        _ask.value = null
        logFeed(at)
        apply(session.handle(SessionEvent.AssistSet(true)))
        refreshUi()
    }

    /** 마지막 수유 시각 [취소]: 기록은 그대로 */
    fun cancelFeedPick() {
        _ask.value = null
        apply(session.handle(SessionEvent.AssistSet(true)))
        refreshUi()
    }

    /** 출발 알림 2: 밥 시간. 정하면 20분 전에 휴게소를 찾아 줌 */
    private fun askMealTime() {
        alerts.show(
            CarAlert(
                AlertKind.PLAN, "🍚 밥은 언제 먹을까요?", "정하면 그때쯤 경로 위 휴게소를 찾아 드려요",
                listOf(
                    AlertButton(AlertActions.mealIn(60), "1시간 뒤"),
                    AlertButton(AlertActions.mealIn(120), "2시간 뒤"),
                    AlertButton(AlertActions.mealIn(180), "3시간 뒤"),
                    AlertButton(AlertActions.MEAL_NONE, "안 먹어요"),
                ),
            ),
        )
    }

    /** 다음 수유 시각 정하기 (출발 알림 · 화면). minutes = null 이면 간격으로 계산한 값 그대로 */
    fun setNextFeedIn(minutes: Int?) {
        apply(session.handle(SessionEvent.FeedDueSet(minutes?.let { clock.now().plusMinutes(it.toLong()) })))
        log(minutes?.let { "다음 수유 ${it}분 뒤로 정함" } ?: "다음 수유는 간격대로")
        refreshUi()
    }

    /** 밥 시간 정하기. minutes = null 이면 안 먹음 */
    fun setMealIn(minutes: Int?) {
        apply(session.handle(SessionEvent.MealSet(minutes?.let { clock.now().plusMinutes(it.toLong()) })))
        log(minutes?.let { "밥 시간 ${it}분 뒤로 정함" } ?: "밥 시간 안 정함")
        refreshUi()
    }

    /** 시스템 알림 버튼 */
    fun onAlertAction(id: String) {
        when {
            id == AlertActions.FEED_KEEP || id.startsWith("feed:in:") -> {
                setNextFeedIn(id.removePrefix("feed:in:").toIntOrNull()?.takeIf { id != AlertActions.FEED_KEEP })
                askMealTime()
            }
            id == AlertActions.MEAL_NONE || id.startsWith("meal:in:") -> {
                setMealIn(id.removePrefix("meal:in:").toIntOrNull()?.takeIf { id != AlertActions.MEAL_NONE })
                alerts.cancel(AlertKind.PLAN)
            }
            id == AlertActions.USE_YES -> answerUse(true)
            id == AlertActions.USE_NO -> answerUse(false)
            id == AlertActions.CARE_APPLY -> applyCare()
            id == AlertActions.CARE_DISMISS -> dismissCare()
            id == AlertActions.MUSIC_OFF -> stopMusic()
            id == AlertActions.ARRIVE_OK -> alerts.cancel(AlertKind.ARRIVE)
            id == AlertActions.ARRIVE_OPEN -> {
                alerts.cancel(AlertKind.ARRIVE)
                requestFocus(Focus.ARRIVE)
                refreshUi()
            }
            id == AlertActions.REST_DISMISS -> {
                alerts.cancel(AlertKind.PLAN)
                dismissRestStops()
            }
            id == AlertActions.REST_SEARCH -> {
                alerts.cancel(AlertKind.PLAN)
                searchNaviPlace(if (offer?.nursing == true) "수유실" else "휴게소")
            }
            id.startsWith("rest:") -> id.removePrefix("rest:").toIntOrNull()?.let { i -> scope.launch { addRestStop(i) } }
        }
    }

    fun logFeed(at: LocalDateTime? = null) {
        // 수유했으면 지난 수유 휴게소 제안은 필요 없음 (다음 제안은 세션이 새 수유 시각으로 판단)
        if (offer?.nursing == true) offer = null
        apply(session.handle(SessionEvent.FeedLogged(at)))
        profiles.saveLastFeed(session.lastFeedAt)
        navi.state.value.route?.let { onRoute(it) }
    }

    /** 휴대폰에서 "○분 전에 먹였어요"로 기록 (0이면 지금) */
    fun logFeedAgo(minutesAgo: Int) {
        val m = minutesAgo.coerceIn(0, 720)
        logFeed(if (m > 0) clock.now().minusMinutes(m.toLong()) else null)
    }

    fun updateProfile(p: BabyProfile) {
        val before = session.profile
        session.updateProfile(p)
        profiles.saveProfile(p)
        // 카메라 옵션만 바꿨으면 좌석 계획은 그대로 (주행 중 제안 · 조치가 지워지지 않게)
        if (before.copy(cameraWatch = p.cameraWatch) != p) session.trip?.let { planTrip(it) }
        if (!p.cameraWatch) dismissCare()
        refreshUi()
    }

    fun wipePersonalData() {
        profiles.wipe()
        session.updateProfile(BabyProfile())
        log("개인정보를 지웠어요")
        refreshUi()
    }

    suspend fun addRestStop(index: Int): Boolean {
        val o = offer ?: return false
        val c = o.candidates.getOrNull(index) ?: return false
        val first = navi.state.value.waypoints.isEmpty()
        val ok = navi.addWaypoint(c.restArea, first)
        if (ok) {
            offer = o.copy(addedName = c.restArea.name)
            alerts.cancel(AlertKind.REST)
            log("경유지 추가: ${c.restArea.name}")
            say("${c.restArea.name}을 경유지로 추가했어요", session.quietMode)
            // 경로 전체 보기는 다시 띄우지 않음 (운전 중 확인 한 번 더 → 사용자 결정 11/15). 안내는 그대로 이어짐
        } else {
            banner = "내비가 경유지를 받지 않았어요 (경유지는 최대 2개)"
        }
        refreshUi()
        return ok
    }

    fun requestRestStops(nursing: Boolean) {
        scope.launch { offerRestStops(nursing, session.minutesUntilFeeding()?.coerceAtLeast(0) ?: 0) }
    }

    fun dismissRestStops() {
        alerts.cancel(AlertKind.REST)
        offer = null
        focus = Focus.NONE
        refreshUi()
    }

    fun clearBanner() {
        banner = null
        refreshUi()
    }

    /** 출발 준비 화면(C-2)을 앞으로 가져옴 */
    fun showBoarding() {
        requestFocus(Focus.BOARDING)
        refreshUi()
    }

    /** 휴대폰 연결 상태 (PhoneLink 가 알려 줌) */
    fun setPhone(p: PhoneStatus) {
        if (p == phone) return
        if (p.connected && !phone.connected) {
            log("휴대폰 연결됨")
            banner = "휴대폰이 연결됐어요 · 휴대폰에서 여정을 보내면 바로 받아요"
        }
        phone = p
        refreshUi()
    }

    fun clearFocus() {
        focus = Focus.NONE
        refreshUi()
    }

    /** 휴게소 제안 등을 닫고 주행 화면으로 (자동 시연에서 씀) */
    fun showDrive() {
        offer = null
        requestFocus(Focus.DRIVE)
        refreshUi()
    }

    fun refreshCapabilities() {
        scope.launch { vehicle.refreshCapabilities() }
        navi.refresh()
    }

    // ---- Gleo ----------------------------------------------------------------------------

    /** Gleo 액션 처리. 호출 쪽에서 5초 제한을 걸고, 여기서는 메인 스레드에서 상태를 바꿈. */
    suspend fun handleGleo(cmd: GleoCommand): GleoCallback = withContext(main) {
        when (cmd) {
            is GleoCommand.PrepareDeparture -> {
                planTrip(cmd.trip, "Gleo")
                val p = session.plan
                val msg = if (session.drivingState == DrivingState.DRIVING) {
                    "The seat plan is ready. I will set the seats after you park."
                } else {
                    "The seat plan is ready on the screen. Press and hold Apply to move the seats."
                }
                GleoCallback(
                    cmd.functionName, GleoStatus.SUCCESS, msg, GleoMode.VOICE,
                    data = listOf(
                        mapOf(
                            "carSeat" to (p?.carSeatSeats?.firstOrNull()?.labelKo ?: ""),
                            "trunk" to (p?.trunkMode?.labelKo ?: ""),
                            "manualSteps" to (p?.guideSteps?.size ?: 0),
                        ),
                    ),
                )
            }

            is GleoCommand.ReportBabyState -> {
                if (cmd.fed) {
                    logFeed()
                    GleoCallback(cmd.functionName, GleoStatus.SUCCESS, "Feeding time is recorded.", GleoMode.VOICE)
                } else {
                    val state = cmd.state ?: BabyState.UNKNOWN
                    reportBaby(state, StateSource.GLEO_VOICE)
                    val (msg, mode) = when (state) {
                        BabyState.ASLEEP -> "Got it. Guidance will stay quiet while the baby sleeps." to GleoMode.BEEP
                        BabyState.CRYING -> "Got it. I am looking for a nearby rest area." to GleoMode.VOICE
                        BabyState.HUNGRY -> "Got it. I am looking for a rest area with a nursing room." to GleoMode.VOICE
                        else -> "Got it. The baby is awake." to GleoMode.VOICE
                    }
                    GleoCallback(cmd.functionName, GleoStatus.SUCCESS, msg, mode, data = listOf(mapOf("babyState" to state.name.lowercase())))
                }
            }

            is GleoCommand.AddNursingStop -> {
                // "예": 출발 알림(Baby Seat 기능 사용?)이 떠 있으면 그 답
                if (askingUse && cmd.choice == null) {
                    answerUse(true)
                    return@withContext GleoCallback(cmd.functionName, GleoStatus.SUCCESS, "Okay. Please choose the time of the last feeding.", GleoMode.BEEP)
                }
                // "적용": 카메라 알림이 떠 있으면 그것부터
                val care = careSuggestion
                if (care != null && cmd.choice == null) {
                    applyCare()
                    return@withContext GleoCallback(
                        cmd.functionName, GleoStatus.SUCCESS,
                        if (care == BabyState.ASLEEP) "Applied. Guidance is quieter and a soft lullaby is playing." else "Applied. Guidance is back to normal and a children's song is playing.",
                        GleoMode.BEEP,
                    )
                }
                if (navi.state.value.route == null) {
                    return@withContext GleoCallback(cmd.functionName, GleoStatus.FAIL, "There is no active route, so I cannot add a rest stop.")
                }
                val recent = offer?.takeIf { it.nursing && Duration.between(it.createdAt, clock.now()).toMinutes() < 10 }
                val o = recent ?: offerRestStops(nursing = true, minutesUntilFeeding = session.minutesUntilFeeding()?.coerceAtLeast(0) ?: 0)
                    ?: return@withContext GleoCallback(cmd.functionName, GleoStatus.FAIL, "I could not get the current location.")
                val index = (cmd.choice ?: 1) - 1
                val c = o.candidates.getOrNull(index)
                    ?: return@withContext GleoCallback(cmd.functionName, GleoStatus.SUCCESS, "No rest area with a nursing room was found along the route.")
                if (addRestStop(index)) {
                    GleoCallback(
                        cmd.functionName, GleoStatus.SUCCESS,
                        "Added ${c.restArea.name} as a waypoint. It is about ${c.etaMinutes} minutes away.",
                        if (session.quietMode) GleoMode.BEEP else GleoMode.VOICE,
                        data = listOf(mapOf("name" to c.restArea.name, "etaMinutes" to c.etaMinutes, "nursingRoom" to c.restArea.hasNursingRoom)),
                    )
                } else {
                    GleoCallback(cmd.functionName, GleoStatus.FAIL, "The navigation did not accept the waypoint.")
                }
            }

            is GleoCommand.Invalid ->
                if (cmd.functionName == GleoContract.REPORT_BABY_STATE.name) GleoContract.askingFor(cmd)
                else GleoCallback(cmd.functionName, GleoStatus.FAIL, "I could not understand the request.")
        }
    }

    // ---- UI 상태 -----------------------------------------------------------------------------

    private fun log(line: String) {
        val stamped = "${clock.now().format(HMS)}  $line"
        synchronized(logLines) {
            logLines.addFirst(stamped)
            while (logLines.size > 60) logLines.removeLast()
        }
    }

    private fun refreshUi() {
        val now = clock.now()
        val route = navi.state.value.route
        val advice = if (session.drivingState != DrivingState.DRIVING && route != null && departedAt == null) {
            predictor.recommendDeparture(now, (route.remainSeconds / 60).toInt(), session.lastFeedAt ?: now, session.profile, history = history)
        } else null
        _ui.value = UiState(
            now = now,
            simulation = simulation,
            clockSpeed = clock.speed,
            vehicle = vehicle.status.value,
            navi = navi.state.value.let { s -> demoHere?.let { s.copy(current = it) } ?: s },
            roadPath = currentRoadPath()?.points.orEmpty(),
            roadDone = if (demoHere != null) currentRoadPath()?.upTo(demoDoneKm).orEmpty() else emptyList(),
            roadHeading = if (demoHere != null) currentRoadPath()?.bearingAt(demoDoneKm) else null,
            driving = driving.state.value,
            voiceEngine = voice.engineName,
            profile = session.profile,
            lastFeedAt = session.lastFeedAt,
            nextFeedAt = session.nextFeedDue(),
            minutesToFeed = session.minutesUntilFeeding(now),
            mealAt = session.nextMealAt(now),
            minutesToMeal = session.minutesUntilMeal(now),
            trip = session.trip,
            plan = session.plan,
            run = runner.state.value,
            pendingSteps = session.pendingSteps,
            baby = session.babyState,
            babySince = if (session.babyState == BabyState.UNKNOWN) null else session.babyStateSince,
            babySource = session.babyStateSource,
            quiet = session.quietMode,
            eta = eta,
            departureAdvice = advice,
            restStops = offer,
            banner = banner,
            focus = focus,
            focusSeq = focusSeq,
            arrived = session.arrived,
            departed = departedAt != null,
            comfort = comfort,
            comfortCountdown = comfortCountdown,
            lastVoice = lastVoice,
            rearSeatAlert = rearSeatAlert,
            tripSource = if (session.trip != null) tripSource else null,
            phone = phone,
            careSuggestion = careSuggestion,
            music = music.playing,
            log = synchronized(logLines) { logLines.toList() },
        )
    }

    companion object {
        private const val COMFORT_ANNOUNCE_SECONDS = 3

        /** 경로가 이만큼 넘게 비어 있으면 끝 · 취소로 봄 (경유지 재탐색 중 잠깐 비는 것과 구분) */
        private const val ROUTE_GONE_MS = 8_000L
        private val HMS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    }
}
