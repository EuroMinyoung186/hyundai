package ai.moduui.jari.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** 아기 상태에 맞춰 트는 노래. 저작권 걱정 없게 오래된 민요 · 자장가 가락을 앱이 직접 만들어 냄 */
enum class Song(val labelKo: String, val volume: Float, val tempoMs: Int) {
    /** 잠들었을 때: 브람스 자장가, 아주 작게 · 느리게 */
    LULLABY("자장가", 0.18f, 520),
    /** 깼을 때: 작은 별, 보통 크기 */
    KIDS("동요 · 작은 별", 0.45f, 380),
}

/** 차 안 스피커로 노래 틀기. 시험에서는 [None] */
interface CabinMusic {
    val playing: Song?
    fun play(song: Song)
    fun stop()
    /** 다른 앱이 소리를 가져가서 스스로 멈췄을 때 (화면의 노래 카드를 내리려고) */
    fun setOnStopped(cb: () -> Unit) = Unit

    object None : CabinMusic {
        override val playing: Song? = null
        override fun play(song: Song) = Unit
        override fun stop() = Unit
    }
}

/**
 * 사인파로 가락을 만들어 AudioTrack 으로 반복 재생. 미디어 오디오 포커스를 받고, 잃으면 멈춤 (Pleos 오디오 정책).
 * 앱은 내비 안내 음량을 바꿀 공개 API 가 없어서(Pleos SDK · AAOS 모두 시스템 권한 필요), 노래와 앱 음성 크기로 조절함.
 */
class SynthCabinMusic(context: Context) : CabinMusic {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    @Volatile private var onStopped: (() -> Unit)? = null
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                stop()
                onStopped?.invoke()
            }
        }
        .build()

    override fun setOnStopped(cb: () -> Unit) {
        onStopped = cb
    }

    @Volatile override var playing: Song? = null
        private set
    @Volatile private var worker: Thread? = null

    override fun play(song: Song) {
        stop()
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "audio focus denied")
            return
        }
        playing = song
        val t = Thread({ loop(song) }, "cabin-music").apply { isDaemon = true }
        worker = t
        t.start()
    }

    override fun stop() {
        playing = null
        worker?.interrupt()
        worker = null
        audio.abandonAudioFocusRequest(focus)
    }

    private fun loop(song: Song) {
        val rate = 22_050
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.setVolume(song.volume)
            track.play()
            val notes = melody(song)
            while (!Thread.currentThread().isInterrupted && playing == song) {
                for ((freq, beats) in notes) {
                    if (Thread.currentThread().isInterrupted || playing != song) break
                    val n = (rate * song.tempoMs * beats / 1000f).toInt()
                    val buf = ShortArray(n)
                    if (freq > 0f) {
                        val attack = rate / 40
                        for (i in 0 until n) {
                            // 부드럽게 시작 · 끝나는 소리 (아기가 놀라지 않게)
                            val env = min(1f, i / attack.toFloat()) * (1f - i / n.toFloat())
                            val s = sin(2 * PI * freq * i / rate) * 0.6 + sin(4 * PI * freq * i / rate) * 0.15
                            buf[i] = (s * env * Short.MAX_VALUE * 0.8).toInt().toShort()
                        }
                    }
                    track.write(buf, 0, n)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "music stopped: ${e.javaClass.simpleName}")
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    companion object {
        private const val TAG = "CabinMusic"
        private const val C4 = 261.63f
        private const val D4 = 293.66f
        private const val E4 = 329.63f
        private const val F4 = 349.23f
        private const val G4 = 392.00f
        private const val A4 = 440.00f
        private const val B4 = 493.88f
        private const val C5 = 523.25f
        private const val REST = 0f

        /** (주파수, 박) */
        fun melody(song: Song): List<Pair<Float, Float>> = when (song) {
            Song.KIDS -> listOf(
                C4 to 1f, C4 to 1f, G4 to 1f, G4 to 1f, A4 to 1f, A4 to 1f, G4 to 2f,
                F4 to 1f, F4 to 1f, E4 to 1f, E4 to 1f, D4 to 1f, D4 to 1f, C4 to 2f,
                G4 to 1f, G4 to 1f, F4 to 1f, F4 to 1f, E4 to 1f, E4 to 1f, D4 to 2f,
                G4 to 1f, G4 to 1f, F4 to 1f, F4 to 1f, E4 to 1f, E4 to 1f, D4 to 2f,
                C4 to 1f, C4 to 1f, G4 to 1f, G4 to 1f, A4 to 1f, A4 to 1f, G4 to 2f,
                F4 to 1f, F4 to 1f, E4 to 1f, E4 to 1f, D4 to 1f, D4 to 1f, C4 to 2f, REST to 2f,
            )
            Song.LULLABY -> listOf(
                E4 to 1f, E4 to 1f, G4 to 3f, E4 to 1f, E4 to 1f, G4 to 3f,
                E4 to 1f, G4 to 1f, C5 to 2f, B4 to 2f, A4 to 2f, A4 to 1f, G4 to 3f,
                D4 to 1f, E4 to 1f, F4 to 2f, D4 to 2f, D4 to 1f, E4 to 1f, F4 to 3f,
                D4 to 1f, F4 to 1f, B4 to 1f, A4 to 1f, G4 to 2f, B4 to 2f, C5 to 4f, REST to 3f,
            )
        }
    }
}
