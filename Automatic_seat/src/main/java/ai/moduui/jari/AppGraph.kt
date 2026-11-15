package ai.moduui.jari

import ai.moduui.core.model.DrivingState
import ai.moduui.jari.car.CarDrivingStateSource
import ai.moduui.jari.car.DrivingStateSource
import ai.moduui.jari.car.FakeDrivingStateSource
import ai.moduui.jari.control.SimClock
import ai.moduui.jari.control.TripController
import ai.moduui.jari.data.ProfileRepository
import ai.moduui.jari.data.RestAreaRepository
import ai.moduui.jari.data.SecureStore
import ai.moduui.jari.navi.FakeNaviGateway
import ai.moduui.jari.navi.NaviGateway
import ai.moduui.jari.navi.PleosNaviGateway
import ai.moduui.jari.phone.PhoneLink
import ai.moduui.jari.service.TripMonitorService
import ai.moduui.jari.vehicle.FakeVehicleGateway
import ai.moduui.jari.vehicle.PleosVehicleGateway
import ai.moduui.jari.vehicle.VehicleGateway
import ai.moduui.jari.voice.VoiceFactory
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 수동 의존성 연결. Pleos 차량 서비스가 있으면 실제 SDK, 없으면 앱 안의 가짜 차량·내비를 씀.
 * Pleos 에뮬레이터에서의 시연은 앱 밖에서 함: Pleos Virtual Console(차량 상태) + 기본 내비(경로) +
 * tools/sim/everyseat-sim.ps1(시나리오). 앱은 실제 SDK 로 그 상태를 읽기만 함.
 */
class AppGraph(private val context: Context) {

    private val prefs = context.getSharedPreferences("moduui_settings", Context.MODE_PRIVATE)

    val pleosServiceInstalled: Boolean = isInstalled(VEHICLE_SERVICE_PACKAGE)
    val forceSimulation: Boolean = prefs.getBoolean(KEY_FORCE_SIM, false)
    val simulation: Boolean = forceSimulation || !pleosServiceInstalled

    /** 에뮬레이터 2.1.0 에서 착좌 Boolean 이 반대로 온다는 공개 실측이 있어 기본값을 켬. 실차에서는 끄고 확인. */
    var occupancyInverted: Boolean
        get() = prefs.getBoolean(KEY_OCC_INVERT, isEmulator())
        set(v) = prefs.edit().putBoolean(KEY_OCC_INVERT, v).apply()

    val clock = SimClock()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val fakeVehicle: FakeVehicleGateway? = if (simulation) FakeVehicleGateway() else null
    val fakeDriving: FakeDrivingStateSource? = if (simulation) FakeDrivingStateSource() else null
    val fakeNavi: FakeNaviGateway?

    private val vehicle: VehicleGateway = fakeVehicle ?: PleosVehicleGateway(context) { occupancyInverted }
    private val driving: DrivingStateSource = fakeDriving ?: CarDrivingStateSource(context)
    private val navi: NaviGateway

    init {
        fakeNavi = if (simulation) FakeNaviGateway(clock::now) { driving.state.value.state == DrivingState.DRIVING } else null
        navi = fakeNavi ?: PleosNaviGateway(context, clock::now)
    }

    val profiles = ProfileRepository(SecureStore(context))
    val restAreas = RestAreaRepository(context)

    /** 시연용 실제 도로 경로 (assets + debug 에서 받아 온 것) */
    val demoRoutes = ai.moduui.jari.navi.DemoRoutes(context)

    /** 내비 위 알림 카드 (다른 앱 위에 그리기 허용 시) + 알림 센터 기록 */
    private val overlayAlerts = ai.moduui.jari.alert.OverlayAlerts(context, ai.moduui.jari.alert.SystemAlerts(context)) { id -> this.controller.onAlertAction(id) }

    val controller: TripController = TripController(
        vehicle = vehicle,
        navi = navi,
        driving = driving,
        voice = VoiceFactory.create(context, BuildConfig.PLEOS_TTS),
        profiles = profiles,
        restAreas = restAreas::all,
        clock = clock,
        simulation = simulation,
        scope = scope,
        onTripActive = { active -> if (active) TripMonitorService.start(context) else TripMonitorService.stop(context) },
        // 내비 위 알림 카드 (다른 앱 위에 그리기 허용 시) + 알림 센터 기록
        alerts = overlayAlerts,
        music = ai.moduui.jari.audio.SynthCabinMusic(context),
        roadPath = { from, to -> demoRoutes.match(from, to) },
        searchRestAreas = { restAreas.searchNow() },
    )

    /** 앱 화면(MainActivity · debug 시연 지도)이 보이는지. 가려져 있을 때만 내비 오른쪽 패널 · 알림을 띄움 */
    val appVisible = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val visibleScreens = mutableSetOf<String>()

    /** 화면 onStart(true) / onStop(false) 에서 부름 */
    fun setScreenVisible(name: String, visible: Boolean) {
        if (visible) visibleScreens += name else visibleScreens -= name
        appVisible.value = visibleScreens.isNotEmpty()
    }

    /** 내비 오른쪽 패널 (앱이 가려져 있고 경로 안내 중일 때) */
    val naviPanel = ai.moduui.jari.alert.NaviSidePanel(context, controller, appVisible, scope) { open ->
        // 패널에 휴게소 제안이 같이 나오므로 휴게소 카드는 걷고, 다른 카드는 패널 위로 다시 놓음
        if (open) overlayAlerts.cancel(ai.moduui.jari.alert.AlertKind.REST)
        overlayAlerts.relayout()
    }

    init {
        // 출발 질문 카드가 뜨거나 사라지면 알림 카드를 그 아래로 다시 쌓음
        scope.launch { ai.moduui.jari.alert.TopRightStack.changed.collect { overlayAlerts.restackNow() } }
    }

    /** 출발 알림 카드 (기능 사용? · 마지막 수유 시각) */
    val startAsk = ai.moduui.jari.alert.StartAskCard(context, controller, scope)

    /** 보호자 휴대폰 연결. 중계 서버 주소가 없는 빌드(출시)에서는 꺼 둠. */
    val phoneLink: PhoneLink? = BuildConfig.PHONE_RELAY_URL.takeIf { it.isNotBlank() }?.let { PhoneLink(it, controller, scope, prefs) }

    val carDrivingSource: CarDrivingStateSource? get() = driving as? CarDrivingStateSource

    fun setForceSimulation(on: Boolean) = prefs.edit().putBoolean(KEY_FORCE_SIM, on).apply()

    private fun isInstalled(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk")

    companion object {
        /** Pleos 공식 샘플이 버전 확인에 쓰는 Vehicle Service 패키지 */
        const val VEHICLE_SERVICE_PACKAGE = "ai.pleos.playground.service.vehicle"
        private const val KEY_FORCE_SIM = "force_simulation"
        private const val KEY_OCC_INVERT = "occupancy_inverted"
    }
}
