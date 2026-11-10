package ai.moduui.jari.car

import ai.moduui.core.model.DrivingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class DrivingSnapshot(
    val state: DrivingState = DrivingState.UNKNOWN,
    /** CarUxRestrictions.isRequiresDistractionOptimization */
    val uxRestricted: Boolean = false,
    val gear: String? = null,
    val speedKmh: Float? = null,
    val source: String = "-",
    val simulated: Boolean = false,
)

/**
 * 주행/정차 판단. Pleos 정책상 속도·기어 같은 표준 데이터는 AAOS Car API 로 읽고,
 * 주행 중 화면 제한은 CarUxRestrictions 를 기준으로 함.
 */
interface DrivingStateSource {
    val state: StateFlow<DrivingSnapshot>
    fun start()
    fun stop()
}

/** 일반 에뮬레이터·테스트용. 개발자 콘솔/adb 로 기어와 속도를 바꿈. */
class FakeDrivingStateSource : DrivingStateSource {
    private val _state = MutableStateFlow(DrivingSnapshot(DrivingState.PARKED, gear = "P", speedKmh = 0f, source = "시뮬레이션", simulated = true))
    override val state: StateFlow<DrivingSnapshot> = _state
    override fun start() = Unit
    override fun stop() = Unit

    fun setGear(gear: String) {
        _state.update {
            val driving = gear != "P"
            it.copy(
                gear = gear,
                state = if (driving) DrivingState.DRIVING else DrivingState.PARKED,
                uxRestricted = driving,
                speedKmh = if (driving) (it.speedKmh?.takeIf { s -> s > 0 } ?: 80f) else 0f,
            )
        }
    }

    /** 주행 중일 때만 반영 (P 단에서는 항상 0) */
    fun setSpeed(kmh: Float) {
        _state.update { if (it.state == DrivingState.DRIVING) it.copy(speedKmh = kmh.coerceIn(1f, 200f)) else it }
    }
}
