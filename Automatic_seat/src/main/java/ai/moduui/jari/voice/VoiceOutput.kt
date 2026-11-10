package ai.moduui.jari.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * 앱이 먼저 말을 거는 안내 (수유 시간, 잠듦 확인 등).
 * Gleo 가 시킨 일의 응답은 Gleo 콜백(mode=voice)으로 Gleo 가 말하므로 여기 쓰지 않음.
 */
interface VoiceOutput {
    val engineName: String
    fun speak(text: String, quiet: Boolean = false)
    fun stop()
    fun release()
}

/**
 * Pleos 오디오 포커스 정책 반영:
 *  - 재생 전 AudioAttributes + AudioFocusRequest 로 포커스 요청 (안내성 알림 → USAGE_NOTIFICATION, GAIN_TRANSIENT_MAY_DUCK)
 *  - LOSS / LOSS_TRANSIENT 면 즉시 멈춤
 *  - 첫 음절 잘림 방지용 무음 180ms (가이드 권장 150~200ms)
 */
class AndroidTtsVoice(context: Context) : VoiceOutput {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }
        .build()

    @Volatile private var ready = false
    @Volatile private var failed = false
    private val pending = ArrayDeque<Pair<String, Boolean>>()
    private val fallback = BeepVoice()

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val r = engine().setLanguage(Locale.KOREAN)
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            failed = !ready
            engine().setAudioAttributes(attrs)
            engine().setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.startsWith("end-") == true) audio.abandonAudioFocusRequest(focusRequest)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    audio.abandonAudioFocusRequest(focusRequest)
                }
            })
            synchronized(pending) {
                while (pending.isNotEmpty()) {
                    val (t, q) = pending.removeFirst()
                    speak(t, q)
                }
            }
        } else {
            failed = true
            Log.w(TAG, "Android TTS init failed: $status")
        }
    }

    private fun engine() = tts

    override val engineName: String
        get() = when {
            ready -> "안드로이드 TTS"
            failed -> "효과음 (TTS 엔진 없음)"
            else -> "TTS 준비 중"
        }

    override fun speak(text: String, quiet: Boolean) {
        if (failed) {
            fallback.speak(text, quiet)
            return
        }
        if (!ready) {
            synchronized(pending) { pending.addLast(text to quiet) }
            return
        }
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.i(TAG, "audio focus denied, skip: $text")
            return
        }
        val id = UUID.randomUUID().toString()
        tts.playSilentUtterance(PRE_ROLL_MS, TextToSpeech.QUEUE_ADD, "pre-$id")
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, if (quiet) 0.35f else 1.0f)
        }
        tts.speak(text, TextToSpeech.QUEUE_ADD, params, "end-$id")
    }

    override fun stop() {
        if (ready) tts.stop()
        audio.abandonAudioFocusRequest(focusRequest)
    }

    override fun release() {
        stop()
        tts.shutdown()
        fallback.release()
    }

    companion object {
        private const val TAG = "Voice"
        const val PRE_ROLL_MS = 180L
    }
}

/** TTS 엔진이 없을 때: 짧은 효과음만 (내용은 화면 배너로) */
class BeepVoice : VoiceOutput {
    private var tone: ToneGenerator? = null
    override val engineName: String = "효과음"

    override fun speak(text: String, quiet: Boolean) {
        val t = tone ?: runCatching { ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, if (quiet) 30 else 80) }.getOrNull()?.also { tone = it }
        t?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    override fun stop() {
        tone?.stopTone()
    }

    override fun release() {
        tone?.release()
        tone = null
    }
}

/** 단위 테스트·무음 환경용 */
class RecordingVoice : VoiceOutput {
    val spoken = mutableListOf<Pair<String, Boolean>>()
    override val engineName: String = "기록"
    override fun speak(text: String, quiet: Boolean) {
        spoken += text to quiet
    }

    override fun stop() = Unit
    override fun release() = Unit
}

object VoiceFactory {
    /**
     * moduui.pleosTts=true 로 빌드하면 PleosTtsVoice(Pleos TextToSpeech SDK, ON_DEVICE 모드)를 먼저 씀.
     * 콘솔에서 TextToSpeech 가 아직 Coming Soon 이라 기본 빌드에는 넣지 않음.
     */
    fun create(context: Context, usePleosTts: Boolean): VoiceOutput {
        if (usePleosTts) {
            val pleos = runCatching {
                Class.forName("ai.moduui.jari.voice.PleosTtsVoice")
                    .getConstructor(Context::class.java)
                    .newInstance(context) as VoiceOutput
            }.onFailure { Log.w("Voice", "PleosTtsVoice unavailable", it) }.getOrNull()
            if (pleos != null) return pleos
        }
        return AndroidTtsVoice(context)
    }
}
