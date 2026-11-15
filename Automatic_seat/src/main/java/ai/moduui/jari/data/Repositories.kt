package ai.moduui.jari.data

import ai.moduui.core.model.BabyProfile
import ai.moduui.core.model.CarSeatDirection
import ai.moduui.core.model.GeoPoint
import ai.moduui.core.model.TimeWindow
import ai.moduui.core.model.TripRecord
import ai.moduui.core.reststop.RestArea
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime

/** 아기 프로필·마지막 수유·지난 여정 기록 (모두 암호화 저장) */
class ProfileRepository(private val store: KeyValueStore) {

    fun loadProfile(): BabyProfile = store.get(KEY_PROFILE)?.let { runCatching { parseProfile(it) }.getOrNull() } ?: BabyProfile()

    fun saveProfile(p: BabyProfile) = store.put(KEY_PROFILE, profileJson(p).toString())

    fun loadLastFeed(): LocalDateTime? = store.get(KEY_LAST_FEED)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    fun saveLastFeed(at: LocalDateTime?) = store.put(KEY_LAST_FEED, at?.toString())

    fun loadHistory(): List<TripRecord> = store.get(KEY_HISTORY)?.let { text ->
        runCatching {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                TripRecord(o.getInt("predicted"), o.getInt("actual"), o.getInt("navi"))
            }
        }.getOrNull()
    } ?: emptyList()

    fun appendHistory(r: TripRecord) {
        val list = (loadHistory() + r).takeLast(30)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("predicted", it.predictedMinutes).put("actual", it.actualMinutes).put("navi", it.naviMinutes)) }
        store.put(KEY_HISTORY, arr.toString())
    }

    /** 설정 > 내 데이터 지우기 (계정·소유자 변경 대응) */
    fun wipe() = store.clearAll()

    companion object {
        private const val KEY_PROFILE = "profile"
        private const val KEY_LAST_FEED = "last_feed"
        private const val KEY_HISTORY = "history"

        fun profileJson(p: BabyProfile): JSONObject = JSONObject()
            .put("nickname", p.nickname)
            .put("ageMonths", p.ageMonths)
            .put("seatDirection", p.seatDirection.name)
            .put("feedIntervalMin", p.feedIntervalMin)
            .put("carSeatReclineAllowance", p.carSeatReclineAllowance)
            .put("cameraWatch", p.cameraWatch)
            .put("naps", JSONArray().apply { p.napWindows.forEach { put("${it.start}-${it.end}") } })
            .put("meals", JSONArray().apply { p.mealTimes.forEach { put(it.toString()) } })

        fun parseProfile(text: String): BabyProfile {
            val o = JSONObject(text)
            val naps = o.optJSONArray("naps")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val parts = arr.getString(i).split("-")
                    runCatching { TimeWindow(LocalTime.parse(parts[0]), LocalTime.parse(parts[1])) }.getOrNull()
                }
            } ?: emptyList()
            return BabyProfile(
                nickname = o.optString("nickname", "아기"),
                ageMonths = o.optInt("ageMonths", 10),
                seatDirection = runCatching { CarSeatDirection.valueOf(o.getString("seatDirection")) }.getOrDefault(CarSeatDirection.REAR_FACING),
                feedIntervalMin = o.optInt("feedIntervalMin", 180),
                napWindows = naps,
                carSeatReclineAllowance = o.optInt("carSeatReclineAllowance", 0).coerceIn(0, 10),
                cameraWatch = o.optBoolean("cameraWatch", false),
                mealTimes = o.optJSONArray("meals")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> runCatching { LocalTime.parse(arr.getString(i)) }.getOrNull() }
                } ?: emptyList(),
            )
        }
    }
}

/**
 * 휴게소 목록. 기본은 assets/rest_areas_demo.json (데모용, verified=false).
 * 실제 서비스는 한국도로공사 휴게소 공공데이터에서 수유실 여부를 받아 같은 형식으로 채움.
 * 오프라인에서도 동작하도록 앱에 내장 (Pleos 하드웨어 접근 정책: 주행 중 네트워크 끊김 대비).
 */
class RestAreaRepository(private val context: Context) {
    @Volatile private var cache: List<RestArea>? = null

    /** 지금 경로 위에서 찾은 휴게소 (debug 시연: 지도 데이터에서 찾아 넣음). 있으면 내장 목록보다 먼저 */
    @Volatile var routeAreas: List<RestArea> = emptyList()

    /**
     * 경로 위 휴게소를 찾는 방법 (debug 시연: 목적지가 정해지면 RouteFetcher 가 넣어 둠).
     * 출발할 때 미리 찾지 않고, 수유 · 밥 30분 전에 휴게소를 제안할 때 찾음 (11/15 사용자: "처음부터 찾는 게 아니라 30분 전에 찾아서 알림").
     */
    @Volatile var search: (suspend () -> List<RestArea>)? = null
    @Volatile private var searched: Any? = null

    /** 휴게소를 제안하기 직전에 부름: 이 경로에서 아직 안 찾았으면 지금 찾음 (오래 걸리면 내장 목록만) */
    suspend fun searchNow() {
        val s = search ?: return
        if (searched === s) return
        val found = kotlinx.coroutines.withTimeoutOrNull(SEARCH_TIMEOUT_MS) { runCatching { s() }.getOrNull() }
        if (search !== s) return // 그사이 목적지가 바뀜
        searched = s
        if (!found.isNullOrEmpty()) routeAreas = found
    }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 20_000L
    }

    fun all(): List<RestArea> {
        val route = routeAreas
        val builtIn = cache ?: load().also { cache = it }
        // 같은 휴게소(이름이 같거나 1km 안)는 한 번만
        return route + builtIn.filter { b ->
            route.none { r -> r.name == b.name || ai.moduui.core.route.RoutePath.km(r.location, b.location) < 1.0 }
        }
    }

    private fun load(): List<RestArea> = try {
        val text = context.assets.open("rest_areas_demo.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(text).getJSONArray("restAreas")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RestArea(
                id = o.getString("id"),
                name = o.getString("name"),
                location = GeoPoint(o.getDouble("lat"), o.getDouble("lng")),
                hasNursingRoom = o.optBoolean("nursingRoom", false),
                address = o.optString("address", ""),
                verified = o.optBoolean("verified", false),
            )
        }
    } catch (e: Exception) {
        Log.e("RestAreaRepository", "load failed", e)
        emptyList()
    }
}
