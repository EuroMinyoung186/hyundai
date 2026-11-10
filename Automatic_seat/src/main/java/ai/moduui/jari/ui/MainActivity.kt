package ai.moduui.jari.ui

import ai.moduui.core.gleo.GleoContract
import ai.moduui.jari.ModuuiApp
import ai.moduui.jari.car.CarDrivingStateSource
import ai.moduui.jari.gleo.GleoDispatcher
import ai.moduui.jari.ui.theme.ModuuiTheme
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

/**
 * 하나뿐인 화면 Activity.
 * Gleo 의 component="activity" 함수(PREPARE_DEPARTURE)도 여기로 들어옴 (intent action = 함수 이름, extra ACTION_PARAM).
 */
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[CarDrivingStateSource.PERMISSION_CAR_SPEED] == true) {
            (application as ModuuiApp).graph.carDrivingSource?.onSpeedPermissionGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ModuuiTheme {
                AppRoot(vm)
            }
        }
        if (savedInstanceState == null) handleGleoIntent(intent)
        requestRuntimePermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGleoIntent(intent)
    }

    private fun handleGleoIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != GleoContract.PREPARE_DEPARTURE.name) return
        // 가이드 예시와 같이 extras 에서 문자열로 읽음
        val param = intent.extras?.getString(GleoContract.ACTION_PARAM_EXTRA)
        GleoDispatcher.dispatch(this, action, param)
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add(CarDrivingStateSource.PERMISSION_CAR_SPEED)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }
}
