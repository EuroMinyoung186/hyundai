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
import ai.moduui.core.session.SmallAdjust
import ai.moduui.jari.vehicle.MoveDirection
import kotlinx.coroutines.Job
import ai.moduui.core.model.TripRecord
import ai.moduui.core.model.TripRequest
import ai.moduui.core.reststop.RestArea
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
    val trip: TripRequest? = null,
    val plan: SeatPlan? = null,
    val run: RunState? = null,
    val pendingSteps: List<SeatStep> = emptyList(),
    val baby: BabyState = BabyState.UNKNOWN,
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
    val log: List<String> = emptyList(),
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
) {
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

    val runner = SeatStepRunner(vehicle, { session.drivingState }, scope, ::log)

    private val _ui = MutableStateFlow(UiState(simulation = simulation))
    val ui: StateFlow<UiState> = _ui

    fun start() {
        profiles.loadLastFeed()?.let { session.handle(SessionEvent.FeedLogged(it)) } // 저장된 값 복원 (안내는 하지 않음)
        vehicle.start()
        navi.start()
        driving.start()

        scope.launch {
            driving.state.collect { snap -> onDriving(snap) }
        }
        scope.launch {
            navi.state.map { it.route }.distinctUntilChanged().collect { r -> if (r != null) onRoute(r) else refreshUi() }
        }
        scope.launch {
            navi.state.collect { refreshUi() }
        }
        scope.launch {
            navi.events.collect { e ->
                when (e) {
                    is NaviEvent.DestinationArrived -> onArrived(e.name)
                    is NaviEvent.WaypointArrived -> log("경유지 도착: ${e.name ?: ""}")
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

    private fun onRoute(route: RouteSnapshot) {
        apply(session.handle(SessionEvent.RouteUpdated(route)))
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
                is CarAction.ExecuteSeatSteps -> if (holding) {
                    // 누르고 있는 동안만: 사람이 버튼을 누르고 있으므로 카운트다운 없이 바로, 다시 누르면 이어서
                    val resume = runner.state.value?.reasonKo == a.reasonKo
                    runner.run(a.steps, a.reasonKo, countdownSeconds = 0, keepUndo = resume)
                } else {
                    runner.run(a.steps, a.reasonKo, a.countdownSeconds)
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
            requestFocus(Focus.ARRIVE)
        }
    }

    private suspend fun offerRestStops(nursing: Boolean, minutesUntilFeeding: Int): RestStopOffer? {
        val route = navi.state.value.route
        val here = navi.currentLocation()
        if (route == null || here == null) {
            banner = "경로나 현재 위치를 몰라 휴게소를 찾지 못했어요"
            refreshUi()
            return null
        }
        val candidates = RestStopSelector.select(
            current = here,
            destination = route.destination,
            remainSeconds = route.remainSeconds,
            minutesUntilFeeding = minutesUntilFeeding,
            restAreas = restAreas(),
            requireNursingRoom = nursing,
        )
        val reason = if (nursing) "수유 ${if (minutesUntilFeeding > 0) "${minutesUntilFeeding}분 전" else "시간"} · 수유실 있는 휴게소" else "아기가 울어요 · 가까운 휴게소"
        val o = RestStopOffer(candidates, reason, nursing, clock.now())
        offer = o
        requestFocus(Focus.REST_STOP)
        if (candidates.isEmpty()) {
            say("경로 근처에 ${if (nursing) "수유실 있는 " else ""}휴게소가 없어요", session.quietMode)
        } else {
            say("${candidates[0].restArea.name}까지 약 ${candidates[0].etaMinutes}분이에요", session.quietMode)
        }
        refreshUi()
        return o
    }

    // ---- 화면·Gleo 에서 부르는 동작 ------------------------------------------------------------

    /** source = null 이면 화면에서 고친 것: 처음 받은 곳(휴대폰 · Gleo)을 그대로 둠 */
    fun planTrip(trip: TripRequest, source: String? = null) {
        tripSource = source ?: tripSource ?: "Gleo"
        if (source == "휴대폰") log("휴대폰에서 여정 받음: ${trip.destinationName ?: ""}")
        offer = null // 지난 여정의 휴게소 제안이 남지 않게
        comfortJob?.cancel()
        comfortCountdown = null
        comfort = emptyList()
        rearSeatAlert = false
        lastVoice = null
        banner = null // 지난 도착의 "뒷좌석 아기를 확인하세요" 같은 알림이 새 여정에 남지 않게
        apply(session.handle(SessionEvent.PlanRequested(trip)))
    }

    fun confirmDeparture() {
        apply(session.handle(SessionEvent.DepartureConfirmed))
        onTripActive(true)
    }

    private var holding = false

    /** 세팅 화면 [적용 · 누르고 있으면 맞춰요]: 누르는 순간 시작. 손을 떼면 [holdApplyStop]. */
    fun holdApplyStart() {
        holding = true
        try {
            apply(session.handle(SessionEvent.DepartureConfirmed))
        } finally {
            holding = false
        }
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
        refreshUi()
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

    /** 외부 시뮬레이터 전용: 다시 시작할 때 내비 경로 정리 */
    fun cancelNaviRoute() {
        navi.cancelRoute()
        refreshUi()
    }

    fun nudgeSeat(seat: SeatId, axis: SeatAxis, dir: MoveDirection) = runner.nudge(seat, axis, dir)

    fun toggleSeatHeat(seat: SeatId) = runner.toggleHeat(seat)

    fun reportBaby(state: BabyState, source: StateSource) = apply(session.handle(SessionEvent.BabyReported(state, source)))

    fun logFeed(at: LocalDateTime? = null) {
        // 수유했으면 지난 수유 휴게소 제안은 필요 없음 (다음 제안은 세션이 새 수유 시각으로 판단)
        if (offer?.nursing == true) offer = null
        apply(session.handle(SessionEvent.FeedLogged(at)))
        profiles.saveLastFeed(session.lastFeedAt)
        navi.state.value.route?.let { onRoute(it) }
    }

    fun updateProfile(p: BabyProfile) {
        session.updateProfile(p)
        profiles.saveProfile(p)
        session.trip?.let { planTrip(it) }
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
            log("경유지 추가: ${c.restArea.name}")
            say("${c.restArea.name}을 경유지로 추가했어요", session.quietMode)
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
            navi = navi.state.value,
            driving = driving.state.value,
            voiceEngine = voice.engineName,
            profile = session.profile,
            lastFeedAt = session.lastFeedAt,
            nextFeedAt = session.nextFeedDue(),
            minutesToFeed = session.minutesUntilFeeding(now),
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
            log = synchronized(logLines) { logLines.toList() },
        )
    }

    companion object {
        private const val COMFORT_ANNOUNCE_SECONDS = 3
        private val HMS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
