package ai.moduui.jari.phone

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { PhoneApp() }
    }
}

@Composable
fun PhoneApp(vm: PhoneVm = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::onHeard)
    }
    val listen: () -> Unit = {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "누가 타고 무엇을 싣나요?")
        try {
            speech.launch(i)
        } catch (_: ActivityNotFoundException) {
            vm.toast("이 휴대폰에서는 음성 인식을 쓸 수 없어요 · 아래 칸에 글로 적어 주세요")
        }
    }
    // 디버그 시험 전용: 마이크가 없는 에뮬레이터에서 말한 문장을 넣어 봄 (am start --es heard "...")
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG) (ctx as? ComponentActivity)?.intent?.getStringExtra("heard")?.let(vm::onHeard)
    }
    LaunchedEffect(ui.toast) {
        ui.toast?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            vm.toastShown()
        }
    }
    BackHandler(enabled = ui.stage == Stage.CONNECT) { vm.backToStart() }
    BackHandler(enabled = ui.stage == Stage.MAIN && ui.tab == Tab.ROAD) { vm.selectTab(Tab.HOME) }

    Box(Modifier.fillMaxSize().background(C.bg).systemBarsPadding().imePadding()) {
        when (ui.stage) {
            Stage.START -> StartScreen(vm::start)
            Stage.CONNECT -> ConnectScreen(ui, vm)
            Stage.MAIN -> MainScreen(ui, vm, listen)
        }
    }
}
