package ai.moduui.jari.gleo

import ai.moduui.core.gleo.GleoCallback
import ai.moduui.core.gleo.GleoCommand
import ai.moduui.core.gleo.GleoContract
import ai.moduui.core.gleo.GleoStatus
import ai.moduui.jari.ModuuiApp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gleo 콜백 보내기 (Pleos "Code Details with Examples" 기준):
 *  action  = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
 *  extras  = "packageName" (우리 패키지), "response" (콜백 JSON)
 * 콜백에는 아기 상태 같은 단어만 담고 개인 식별 정보는 넣지 않음 (암시적 브로드캐스트이므로).
 */
object GleoCallbackSender {
    private const val TAG = "Gleo"

    fun send(context: Context, callback: GleoCallback) {
        val intent = Intent(GleoContract.CALLBACK_ACTION).apply {
            putExtra(GleoContract.CALLBACK_EXTRA_PACKAGE, context.packageName)
            putExtra(GleoContract.CALLBACK_EXTRA_RESPONSE, callback.toJson())
        }
        runCatching { context.sendBroadcast(intent) }
            .onSuccess {
                // 개인정보 로그 금지 정책: 전체 JSON 은 디버그 빌드에서만
                if (ai.moduui.jari.BuildConfig.DEBUG) Log.i(TAG, "callback ${callback.toJson()}")
                else Log.i(TAG, "callback ${callback.functionName} ${callback.status.json}")
            }
            .onFailure { Log.e(TAG, "callback failed", it) }
    }
}

/** Gleo 명령을 컨트롤러로 넘기고 7초 제한 전에 콜백을 보냄 */
object GleoDispatcher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun dispatch(context: Context, action: String?, actionParam: String?, onDone: () -> Unit = {}) {
        val app = context.applicationContext as ModuuiApp
        val command = GleoContract.toCommand(action, actionParam)
        scope.launch {
            val callback = try {
                withTimeoutOrNull(GleoContract.RESPONSE_BUDGET_MS) { app.graph.controller.handleGleo(command) }
                    ?: GleoCallback(command.functionName, GleoStatus.FAIL, "The request took too long. Please try again.")
            } catch (e: Exception) {
                Log.e("Gleo", "handle failed", e)
                GleoCallback(command.functionName, GleoStatus.FAIL, "Something went wrong in the app.")
            }
            if (command !is GleoCommand.Invalid || command.functionName.isNotEmpty()) {
                GleoCallbackSender.send(app, callback)
            }
            onDone()
        }
    }
}

/**
 * component = "service" 인 함수 (REPORT_BABY_STATE, ADD_NURSING_STOP).
 * Gleo 가 sendBroadcast 로 보냄. Gleo 가 불러야 하므로 exported 이고, 받은 값은 전부 검증함.
 */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        GleoDispatcher.dispatch(context, intent.action, intent.getStringExtra(GleoContract.ACTION_PARAM_EXTRA)) {
            pending.finish()
        }
    }
}
