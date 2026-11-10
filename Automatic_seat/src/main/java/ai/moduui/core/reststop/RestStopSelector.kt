package ai.moduui.core.reststop

import ai.moduui.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 휴게소. 수유실 여부는 한국도로공사 휴게소 편의시설 공공데이터로 채우는 것을 전제로 함. */
data class RestArea(
    val id: String,
    val name: String,
    val location: GeoPoint,
    val hasNursingRoom: Boolean,
    val address: String = "",
    /** 공공데이터로 확인된 값인지. 데모 데이터는 false. */
    val verified: Boolean = false,
)

data class RestStopCandidate(
    val restArea: RestArea,
    val distanceKm: Double,
    val etaMinutes: Int,
    val detourKm: Double,
)

/**
 * 수유 시간에 맞는 수유실 휴게소 고르기.
 *
 * NaviHelper에는 POI 검색 API가 없으므로 휴게소 목록은 앱이 갖고 있고,
 * 현재 위치(getCurrentLocationInfo)·목적지(getDestinationInfo)·남은 시간으로 경로 근처 후보를 거름.
 */
object RestStopSelector {

    /** 경로에서 벗어나는 정도 허용치: (현재→휴게소→목적지) 거리가 직선의 최대 115% */
    const val MAX_DETOUR_RATIO = 1.15
    const val MIN_DISTANCE_KM = 2.0

    fun select(
        current: GeoPoint,
        destination: GeoPoint?,
        remainSeconds: Long,
        minutesUntilFeeding: Int,
        restAreas: List<RestArea>,
        maxResults: Int = 2,
        requireNursingRoom: Boolean = true,
    ): List<RestStopCandidate> {
        if (remainSeconds <= 0) return emptyList()
        val direct = destination?.let { haversineKm(current, it) }
        // 평균 속도는 내비 남은 거리/시간에서 추정. 목적지를 모르면 고속도로 평균 80km/h 가정.
        val kmPerMin = if (direct != null && direct > 1.0) (direct / (remainSeconds / 60.0)).coerceIn(0.3, 2.0) else 80.0 / 60
        return restAreas.asSequence()
            .filter { !requireNursingRoom || it.hasNursingRoom }
            .mapNotNull { ra ->
                val d = haversineKm(current, ra.location)
                if (d < MIN_DISTANCE_KM) return@mapNotNull null
                val detour = if (destination != null && direct != null) {
                    val via = d + haversineKm(ra.location, destination)
                    if (via > direct * MAX_DETOUR_RATIO) return@mapNotNull null
                    via - direct
                } else 0.0
                val eta = (d / kmPerMin).roundToInt()
                if (eta * 60L >= remainSeconds) return@mapNotNull null
                RestStopCandidate(ra, d, eta, detour)
            }
            // 수유 예정 시각에 가장 가까운 곳, 같으면 덜 돌아가는 곳
            .sortedWith(compareBy<RestStopCandidate>({ abs(it.etaMinutes - minutesUntilFeeding) }, { it.detourKm }))
            .take(maxResults)
            .toList()
    }

    fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371.0
        val dLat = (b.lat - a.lat) * PI / 180
        val dLng = (b.lng - a.lng) * PI / 180
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(a.lat * PI / 180) * cos(b.lat * PI / 180) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(h))
    }
}
