package ai.moduui.jari.debug

import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.constant.Mode
import ai.pleos.playground.llm.listener.OnServerConnectionListener
import ai.pleos.playground.llm.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 디버그 빌드 전용: Gleo LLM 이 이 환경에서 실제로 답하는지 확인. 결과는 logcat 태그 ModuuiLlm.
 *
 *  차 안 모델:  ... --es cmd llm
 *  서버 모델:  ... --es cmd llm --es mode server [--es clientId X --es clientSecret Y]
 *
 * 합성 예시 문장만 보냄. 좌석 판단에는 쓰지 않음.
 */
object LlmProbe {
    private const val TAG = "ModuuiLlm"
    private const val INIT_WAIT_MS = 5_000L
    private const val PROMPT =
        "너는 차량 좌석 배치 도우미야. 아래 후보 중 하나를 고르고 JSON 한 줄만 출력해.\n" +
            "탑승자: 운전자 1, 아기 1(카시트), 무릎이 안 좋은 할머니 1. 짐: 유모차 1, 캐리어 2.\n" +
            "후보 A: 카시트 2열 오른쪽, 할머니 2열 왼쪽(아기 옆)\n" +
            "후보 B: 카시트 2열 오른쪽, 할머니 조수석\n" +
            "형식: {\"choice\":\"A\",\"reason\":\"이유 한 문장\"}"

    private val clients = mutableMapOf<Mode, LLM>()

    fun run(context: Context, server: Boolean, clientId: String?, clientSecret: String?) {
        val mode = if (server) Mode.SERVER else Mode.ON_DEVICE
        val startedAt = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - startedAt

        val existing = clients[mode]
        val client = existing ?: LLM(context, mode).also {
            Log.i(TAG, "$mode initialize")
            it.initialize()
            clients[mode] = it
        }

        fun ask() = runCatching {
            Log.i(TAG, "$mode generateContent at ${elapsed()}ms")
            client.generateContent(
                PROMPT,
                object : ResultListener {
                    override fun onResponse(response: String, completed: Boolean) {
                        Log.i(TAG, "$mode response at ${elapsed()}ms completed=$completed: $response")
                    }

                    override fun onError(error: String) {
                        Log.e(TAG, "$mode error at ${elapsed()}ms: $error")
                    }
                },
            )
        }.onFailure { Log.e(TAG, "$mode generateContent threw", it) }

        Handler(Looper.getMainLooper()).postDelayed(
            {
                if (clientId != null && clientSecret != null) {
                    client.registerApp(
                        clientId,
                        clientSecret,
                        object : OnServerConnectionListener {
                            override fun onConnected() {
                                Log.i(TAG, "$mode registerApp connected at ${elapsed()}ms")
                                ask()
                            }

                            override fun onFailed(error: String) {
                                Log.e(TAG, "$mode registerApp failed at ${elapsed()}ms: $error")
                            }
                        },
                    )
                } else {
                    ask()
                }
            },
            if (existing == null) INIT_WAIT_MS else 0L,
        )
    }
}
