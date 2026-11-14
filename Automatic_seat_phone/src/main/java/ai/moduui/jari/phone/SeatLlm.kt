package ai.moduui.jari.phone

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 폰 안에서 도는 작은 LLM (Qwen3 0.6B, 4비트, 343MB, LiteRT-LM CPU).
 *
 * 빠르게 하려고:
 *  - 엔진은 한 번 띄우면 계속 둠 (처음 띄우기가 수십 초). 화면에 들어오면 warmUp() 으로 미리 띄움
 *  - 규칙(시스템 지시)은 매번 같아서, 그 부분을 미리 계산해 둔 대화(KV 캐시)를 만들어 두고
 *    요청마다 clone() 해서 사람 · 좌석 부분만 새로 계산함
 *  - cacheDir 로 두 번째부터 모델 준비가 빨라짐, 답은 짧게 (maxOutputToken)
 * 모델은 앱에 넣지 않고 처음 쓸 때 받음 (앱 전용 폴더, 권한 필요 없음).
 */
class SeatLlm(private val ctx: Context) {
    val modelFile: File get() = File(ctx.getExternalFilesDir(null), MODEL_FILE)

    val installed: Boolean get() = modelFile.length() > MIN_BYTES

    private val lock = Mutex()
    private var engine: Engine? = null

    /** 규칙까지 미리 계산해 둔 대화. 요청마다 clone() */
    private var base: Conversation? = null

    /** 마지막으로 모델 준비에 걸린 시간 (ms). 이미 떠 있었으면 0 */
    var lastLoadMs: Long = 0
        private set

    val ready: Boolean get() = base != null

    private fun load() {
        if (base != null) {
            lastLoadMs = 0
            return
        }
        val t0 = System.currentTimeMillis()
        val e = engine ?: Engine(EngineConfig(modelPath = modelFile.path, backend = Backend.CPU(), cacheDir = ctx.cacheDir.path)).also {
            it.initialize()
            engine = it
        }
        base = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(PlanPrompt.SYSTEM),
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2),
                extraContext = mapOf("enable_thinking" to false),
                // 규칙 부분을 대화를 만들 때 바로 계산해 KV 캐시에 넣어 둠
                prefillPrefaceOnInit = true,
                maxOutputToken = MAX_OUT,
                thinkingConfig = ThinkingConfig(false),
            ),
        )
        lastLoadMs = System.currentTimeMillis() - t0
        Log.i(TAG, "loaded in ${lastLoadMs}ms")
    }

    /** 화면에 들어왔을 때 미리 띄워 둠 (이미 떠 있으면 바로 끝) */
    suspend fun warmUp() = withContext(Dispatchers.Default) {
        if (!installed) return@withContext
        lock.withLock { runCatching { load() }.onFailure { Log.w(TAG, "warmUp failed: ${it.message}") } }
    }

    /**
     * 규칙이 들어간 대화를 복제해서 block 안에서 send(질문) → 답 글자.
     * 같은 block 안의 send 는 같은 대화 (다시 시키기용).
     */
    suspend fun <T> chat(onLoaded: () -> Unit = {}, block: suspend (send: (String) -> String) -> T): T = withContext(Dispatchers.Default) {
        lock.withLock {
            load()
            onLoaded()
            val conv = base!!.clone()
            try {
                block { prompt ->
                    val t0 = System.currentTimeMillis()
                    val msg = conv.sendMessage(prompt, extraContext = mapOf("enable_thinking" to false))
                    val text = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    Log.i(TAG, "answer in ${System.currentTimeMillis() - t0}ms: ${text.replace('\n', '|')}")
                    text
                }
            } finally {
                conv.close()
            }
        }
    }

    /**
     * 좌석 말고 다른 짧은 질문 (아기 상태 버튼의 할 일 추천 등). 같은 엔진에 지시문만 다른 대화를 잠깐 열어 씀.
     */
    suspend fun askOnce(system: String, prompt: String, maxOut: Int = 40): String = withContext(Dispatchers.Default) {
        lock.withLock {
            load()
            val conv = engine!!.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2),
                    extraContext = mapOf("enable_thinking" to false),
                    maxOutputToken = maxOut,
                    thinkingConfig = ThinkingConfig(false),
                ),
            )
            try {
                val t0 = System.currentTimeMillis()
                val msg = conv.sendMessage(prompt, extraContext = mapOf("enable_thinking" to false))
                val text = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                Log.i(TAG, "askOnce in ${System.currentTimeMillis() - t0}ms: ${text.replace('\n', '|')}")
                text
            } finally {
                conv.close()
            }
        }
    }

    /** 메모리 돌려주기 (앱을 닫을 때) */
    fun release() {
        runCatching { base?.close() }
        runCatching { engine?.close() }
        base = null
        engine = null
    }

    /** 모델 받기 (와이파이 권장). onProgress(0..100). 받다가 끊기면 지우고 false */
    suspend fun download(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val part = File(modelFile.path + ".part")
        runCatching {
            val c = URL(MODEL_URL).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            val total = c.contentLengthLong.takeIf { it > 0 } ?: MODEL_BYTES
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val p = (done * 100 / total).toInt().coerceIn(0, 100)
                        if (p != last) onProgress(p).also { last = p }
                    }
                }
            }
            c.disconnect()
            part.length() > MIN_BYTES && part.renameTo(modelFile)
        }.getOrElse { false }.also { if (!it) part.delete() }
    }

    companion object {
        private const val TAG = "SeatLlm"
        const val MODEL_FILE = "seat-ai.litertlm"
        const val MODEL_NAME = "Qwen3 0.6B"
        const val MODEL_URL = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
        const val MODEL_BYTES = 343_490_560L
        private const val MIN_BYTES = 300_000_000L

        /** 좌석 6줄이면 충분 */
        private const val MAX_OUT = 80
    }
}
