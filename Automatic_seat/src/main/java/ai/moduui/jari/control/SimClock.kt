package ai.moduui.jari.control

import java.time.LocalDateTime

/**
 * 시연·테스트용 시계. speed=1 이면 실제 시간, 60 이면 실제 1초가 1분.
 * 시뮬레이션 모드에서만 배속을 바꿀 수 있음 (실차 연결 시 항상 1배).
 */
class SimClock(private val nanoTime: () -> Long = System::nanoTime) {
    @Volatile private var baseNanos = nanoTime()
    @Volatile private var baseTime: LocalDateTime = LocalDateTime.now()
    @Volatile var speed: Int = 1
        private set

    fun now(): LocalDateTime {
        val elapsed = nanoTime() - baseNanos
        return baseTime.plusNanos(elapsed * speed)
    }

    @Synchronized
    fun setSpeed(newSpeed: Int) {
        val current = now()
        baseTime = current
        baseNanos = nanoTime()
        speed = newSpeed.coerceIn(1, 600)
    }

    @Synchronized
    fun jumpTo(time: LocalDateTime) {
        baseTime = time
        baseNanos = nanoTime()
    }
}
