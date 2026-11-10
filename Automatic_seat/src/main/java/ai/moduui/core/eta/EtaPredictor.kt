package ai.moduui.core.eta

import ai.moduui.core.model.BabyProfile
import ai.moduui.core.model.TripRecord
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToInt

enum class StopType(val labelKo: String, val minutes: Int) {
    FEEDING("수유", 25),
    DIAPER("기저귀", 10),
}

data class PlannedStop(val type: StopType, val at: LocalDateTime, val afterDriveMinutes: Int)

data class EtaPrediction(
    val departAt: LocalDateTime,
    val naviDriveMinutes: Int,
    val naviArrival: LocalDateTime,
    val ruleTotalMinutes: Int,
    val correctionMinutes: Int,
    val predictedArrival: LocalDateTime,
    val stops: List<PlannedStop>,
    val awakeDriveMinutes: Int,
    val cryBufferMinutes: Int,
) {
    val predictedTotalMinutes: Int get() = ruleTotalMinutes + correctionMinutes
    val extraMinutes: Int get() = predictedTotalMinutes - naviDriveMinutes
}

data class DepartureAdvice(val departAt: LocalDateTime, val prediction: EtaPrediction, val reasonKo: String)

/**
 * 아기 동반 도착 시간 예측.
 *
 * 내비 ETA(NaviHelper DestinationInfo.remainTime)는 차가 달리는 시간만 알려 줌.
 * 아기와 함께면 수유·기저귀·달래기 정차가 더해지므로, 수유 간격과 낮잠 시간대로 정차를 시뮬레이션하고
 * 지난 여정 기록(예측 vs 실제)의 중앙값 오차로 개인 보정을 더함.
 */
class EtaPredictor(
    private val stepMinutes: Int = 5,
    private val feedLeadMinutes: Int = 20,
    private val maxFeedDeferWhileAsleepMinutes: Int = 30,
    private val diaperEveryMinutes: Int = 120,
) {

    fun predict(
        departAt: LocalDateTime,
        naviDriveMinutes: Int,
        lastFeedAt: LocalDateTime,
        profile: BabyProfile,
        history: List<TripRecord> = emptyList(),
    ): EtaPrediction {
        require(naviDriveMinutes >= 0) { "naviDriveMinutes must be >= 0" }
        var t = departAt
        var driven = 0
        var nextFeed = lastFeedAt.plusMinutes(profile.feedIntervalMin.toLong())
        var sinceDiaper = 0
        var awake = 0
        val stops = mutableListOf<PlannedStop>()

        while (driven < naviDriveMinutes) {
            val sleeping = profile.napWindows.any { t.toLocalTime() in it }
            val remaining = naviDriveMinutes - driven
            val feedWindowOpen = !t.isBefore(nextFeed.minusMinutes(feedLeadMinutes.toLong()))
            if (feedWindowOpen && remaining > feedLeadMinutes) {
                val canDefer = sleeping && t.isBefore(nextFeed.plusMinutes(maxFeedDeferWhileAsleepMinutes.toLong()))
                if (!canDefer) {
                    stops += PlannedStop(StopType.FEEDING, t, driven)
                    t = t.plusMinutes(StopType.FEEDING.minutes.toLong())
                    nextFeed = t.plusMinutes(profile.feedIntervalMin.toLong())
                    sinceDiaper = 0
                    continue
                }
            }
            if (sinceDiaper >= diaperEveryMinutes && !sleeping && remaining > 30) {
                stops += PlannedStop(StopType.DIAPER, t, driven)
                t = t.plusMinutes(StopType.DIAPER.minutes.toLong())
                sinceDiaper = 0
                continue
            }
            val step = minOf(stepMinutes, remaining)
            t = t.plusMinutes(step.toLong())
            driven += step
            sinceDiaper += step
            if (!sleeping) awake += step
        }

        // 깨어 있는 주행 1시간마다 달래기 정차 기대값 (어릴수록 큼)
        val cryRatePerHour = if (profile.ageMonths < 12) 0.5 else 0.3
        val cryBuffer = (awake / 60.0 * cryRatePerHour * CRY_STOP_MINUTES).roundToInt()
        t = t.plusMinutes(cryBuffer.toLong())

        val ruleTotal = Duration.between(departAt, t).toMinutes().toInt()
        val correction = personalCorrection(history)
        return EtaPrediction(
            departAt = departAt,
            naviDriveMinutes = naviDriveMinutes,
            naviArrival = departAt.plusMinutes(naviDriveMinutes.toLong()),
            ruleTotalMinutes = ruleTotal,
            correctionMinutes = correction,
            predictedArrival = departAt.plusMinutes((ruleTotal + correction).toLong()),
            stops = stops,
            awakeDriveMinutes = awake,
            cryBufferMinutes = cryBuffer,
        )
    }

    /**
     * 출발 후보(15분 간격)를 돌려 보며 '깨어 있는 주행 + 정차×1.5 + 늦은 출발 패널티'가 가장 작은 시각을 고름.
     * 낮잠 시간대에 오래 달리도록 출발을 맞추는 것이 목적.
     */
    fun recommendDeparture(
        earliest: LocalDateTime,
        naviDriveMinutes: Int,
        lastFeedAt: LocalDateTime,
        profile: BabyProfile,
        windowHours: Int = 3,
        history: List<TripRecord> = emptyList(),
    ): DepartureAdvice {
        var best: Triple<Double, LocalDateTime, EtaPrediction>? = null
        for (k in 0..windowHours * 4) {
            val depart = earliest.plusMinutes(15L * k)
            val p = predict(depart, naviDriveMinutes, lastFeedAt, profile, history)
            val stopMinutes = p.stops.sumOf { it.type.minutes }
            val cost = p.awakeDriveMinutes + 1.5 * stopMinutes + 0.2 * 15 * k
            if (best == null || cost < best.first) best = Triple(cost, depart, p)
        }
        val (_, depart, p) = best!!
        return DepartureAdvice(depart, p, "깨어 있는 주행 ${p.awakeDriveMinutes}분, 정차 ${p.stops.size}회")
    }

    companion object {
        const val CRY_STOP_MINUTES = 8

        /** 지난 여정의 (실제 - 예측) 중앙값. 기록이 3개 미만이면 0. 과보정을 막으려고 -15..45분으로 자름. */
        fun personalCorrection(history: List<TripRecord>): Int {
            if (history.size < 3) return 0
            val diffs = history.takeLast(20).map { it.actualMinutes - it.predictedMinutes }.sorted()
            val mid = diffs.size / 2
            val median = if (diffs.size % 2 == 1) diffs[mid].toDouble() else (diffs[mid - 1] + diffs[mid]) / 2.0
            return median.roundToInt().coerceIn(-15, 45)
        }
    }
}
