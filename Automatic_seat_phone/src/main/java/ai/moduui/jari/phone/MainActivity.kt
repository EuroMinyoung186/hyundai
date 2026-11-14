package ai.moduui.jari.phone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { PhoneApp() }
        debugHeard(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugHeard(intent)
    }

    /** 디버그 시험 전용: 마이크가 없는 에뮬레이터에서 말한 문장을 넣어 봄 (am start -f 0x20000000 --es heard "...") */
    private fun debugHeard(i: Intent?) {
        if (BuildConfig.DEBUG) i?.getStringExtra("heard")?.let { DEBUG_HEARD.tryEmit(it) }
    }

    companion object {
        val DEBUG_HEARD = kotlinx.coroutines.flow.MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)
    }
}

@Composable
fun PhoneApp(vm: PhoneVm = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    // 차 화면의 연결 QR 찍기
    val qr = rememberLauncherForActivityResult(ScanContract()) { res -> vm.onQr(res.contents) }
    val scanQr: () -> Unit = {
        qr.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("차 화면 오른쪽 위의 QR을 비춰 주세요")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }
    LaunchedEffect(Unit) {
        MainActivity.DEBUG_HEARD.collect {
            vm.onHeard(it)
            MainActivity.DEBUG_HEARD.resetReplayCache()
        }
    }
    LaunchedEffect(ui.toast) {
        ui.toast?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            vm.toastShown()
        }
    }
    BackHandler(enabled = ui.stage == Stage.CONNECT || ui.stage == Stage.BABY) { vm.backToStart() }
    BackHandler(enabled = ui.stage == Stage.LINKED) { vm.backToCode() }
    BackHandler(enabled = ui.stage == Stage.MAIN && ui.editing) { vm.closeEditor() }
    BackHandler(enabled = ui.stage == Stage.MAIN && !ui.editing && ui.careOpen) { vm.closeCare() }
    // 히스토리(설정 안) → 설정, 그 밖의 탭 → 홈
    BackHandler(enabled = ui.stage == Stage.MAIN && !ui.editing && !ui.careOpen && ui.tab != Tab.HOME) {
        vm.selectTab(if (ui.tab == Tab.HISTORY) Tab.SETTINGS else Tab.HOME)
    }

    Box(Modifier.fillMaxSize().background(C.bg).systemBarsPadding().imePadding()) {
        when (ui.stage) {
            Stage.START -> StartScreen(vm::start)
            Stage.BABY -> BabySetupScreen(vm)
            Stage.CONNECT -> ConnectScreen(ui, vm, scanQr)
            Stage.LINKED -> LinkedScreen(ui, vm)
            Stage.MAIN -> when {
                ui.editing -> TripEditScreen(ui, vm)
                ui.careOpen -> CareScreen(ui, vm)
                else -> MainScreen(ui, vm)
            }
        }
    }
}
