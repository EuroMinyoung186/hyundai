package ai.moduui.jari.car

import ai.moduui.core.model.DrivingState
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * AAOS 표준 경로로 주행 상태를 읽음 (Pleos SDK API 정책: 속도·기어는 AAOS Car API 사용).
 *  - CarUxRestrictionsManager: 주행 중 화면 제한 여부 (Pleos 주행 제한 개발 가이드)
 *  - CarPropertyManager: GEAR_SELECTION (android.car.permission.CAR_POWERTRAIN, normal)
 *                        PERF_VEHICLE_SPEED (android.car.permission.CAR_SPEED, 런타임 허용 필요)
 * Virtual Console 의 Motion > Gear Selection(P/R/N/D)으로 바꿔 볼 수 있음.
 */
class CarDrivingStateSource(context: Context) : DrivingStateSource {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(DrivingSnapshot(source = "AAOS Car API"))
    override val state: StateFlow<DrivingSnapshot> = _state

    private var car: Car? = null
    private var ux: CarUxRestrictionsManager? = null
    private var props: CarPropertyManager? = null
    @Volatile private var gear: Int? = null
    @Volatile private var speedMs: Float? = null
    @Volatile private var uxRestricted = false

    private val propertyCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> gear = value.value as? Int
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> speedMs = (value.value as? Float)
            }
            publish()
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "property error $propertyId")
        }
    }

    override fun start() {
        if (car != null) return
        try {
            car = Car.createCar(appContext, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (!ready) {
                    _state.update { it.copy(source = "Car 서비스 연결 대기") }
                    return@createCar
                }
                connectManagers(connected)
            }
        } catch (t: Throwable) {
            // android.car 가 없는 일반 폰 이미지 등
            Log.w(TAG, "Car API unavailable", t)
            _state.update { it.copy(source = "Car API 없음") }
        }
    }

    private fun connectManagers(c: Car) {
        try {
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            ux = m
            uxRestricted = m.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: false
            m.registerListener { r ->
                uxRestricted = r.isRequiresDistractionOptimization
                publish()
            }
        } catch (e: Exception) {
            Log.w(TAG, "CarUxRestrictionsManager", e)
        }
        try {
            val p = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            props = p
            runCatching { gear = p.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) }
            runCatching {
                p.registerCallback(propertyCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            }
            if (hasSpeedPermission()) {
                runCatching {
                    p.registerCallback(propertyCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "CarPropertyManager", e)
        }
        publish()
    }

    private fun hasSpeedPermission() =
        ContextCompat.checkSelfPermission(appContext, PERMISSION_CAR_SPEED) == PackageManager.PERMISSION_GRANTED

    /** 런타임 권한이 나중에 허용됐을 때 속도 구독 추가 */
    fun onSpeedPermissionGranted() {
        val p = props ?: return
        runCatching { p.registerCallback(propertyCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL) }
    }

    private fun publish() {
        val g = gear
        val kmh = speedMs?.let { it * 3.6f }
        val st = when {
            g == VehicleGear.GEAR_PARK -> DrivingState.PARKED
            g != null && g != VehicleGear.GEAR_UNKNOWN -> DrivingState.DRIVING
            kmh != null -> if (kmh > 1f) DrivingState.DRIVING else DrivingState.PARKED
            uxRestricted -> DrivingState.DRIVING
            else -> DrivingState.UNKNOWN
        }
        _state.update {
            it.copy(state = st, uxRestricted = uxRestricted, gear = g?.let(::gearLabel), speedKmh = kmh, source = "AAOS Car API")
        }
    }

    override fun stop() {
        runCatching { props?.unregisterCallback(propertyCallback) }
        runCatching { ux?.unregisterListener() }
        runCatching { car?.disconnect() }
        car = null
        props = null
        ux = null
    }

    companion object {
        private const val TAG = "DrivingState"
        const val PERMISSION_CAR_SPEED = "android.car.permission.CAR_SPEED"

        fun gearLabel(g: Int): String = when (g) {
            VehicleGear.GEAR_PARK -> "P"
            VehicleGear.GEAR_REVERSE -> "R"
            VehicleGear.GEAR_NEUTRAL -> "N"
            VehicleGear.GEAR_UNKNOWN -> "?"
            else -> "D"
        }
    }
}
