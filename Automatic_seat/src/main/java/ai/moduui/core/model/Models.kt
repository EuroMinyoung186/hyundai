package ai.moduui.core.model

import java.time.LocalDateTime
import java.time.LocalTime

/** 좌석 위치. 국내 좌핸들 기준 ROW_1_LEFT = 운전석. Pleos SeatValue 와 1:1 대응. */
enum class SeatId(val row: Int, val side: Side, val labelKo: String) {
    R1_LEFT(1, Side.LEFT, "운전석"),
    R1_RIGHT(1, Side.RIGHT, "조수석"),
    R2_LEFT(2, Side.LEFT, "2열 왼쪽"),
    R2_RIGHT(2, Side.RIGHT, "2열 오른쪽"),
    R3_LEFT(3, Side.LEFT, "3열 왼쪽"),
    R3_RIGHT(3, Side.RIGHT, "3열 오른쪽");

    val isDriver: Boolean get() = this == R1_LEFT

    /** 같은 쪽 바로 앞 좌석 (후방 카시트 공간 확보 대상) */
    fun seatInFront(): SeatId? = entries.firstOrNull { it.row == row - 1 && it.side == side }

    fun otherSideSameRow(): SeatId = entries.first { it.row == row && it.side != side }
}

enum class Side { LEFT, RIGHT }

enum class CarSeatDirection(val labelKo: String) {
    REAR_FACING("후방"),
    FORWARD_FACING("전방"),
    BOOSTER("부스터"),
}

/** 하루 중 시간 구간 (낮잠 시간대 등). 자정을 넘지 않는 구간만 다룸. */
data class TimeWindow(val start: LocalTime, val end: LocalTime) {
    init {
        require(end.isAfter(start)) { "end must be after start" }
    }

    operator fun contains(time: LocalTime): Boolean = !time.isBefore(start) && time.isBefore(end)
}

/** 아기 프로필. 개인정보이므로 앱에서는 암호화 저장함. */
data class BabyProfile(
    val nickname: String = "아기",
    val ageMonths: Int = 10,
    val seatDirection: CarSeatDirection = CarSeatDirection.REAR_FACING,
    val feedIntervalMin: Int = 180,
    val napWindows: List<TimeWindow> = listOf(TimeWindow(LocalTime.of(13, 0), LocalTime.of(15, 0))),
    /**
     * 아기가 탄 카시트 좌석 등받이를 주행 중에 움직여도 되는 폭 (좌석 범위의 %).
     * 카시트 제조사 허용 각도를 앱이 알 수 없으므로 보호자가 설명서를 보고 넣음. 0 = 움직이지 않음.
     */
    val carSeatReclineAllowance: Int = 0,
    /**
     * 선택 기능: 차에 연결된 카메라가 아기 잠듦 · 깸을 알려 주면 알림으로 띄우고 [적용]을 받음.
     * 끄면 카메라(아기 모니터)가 보낸 상태는 쓰지 않음. 기본은 꺼짐 (사용자가 직접 켬).
     */
    val cameraWatch: Boolean = false,
    /** 아기 밥(이유식) 시간. 어느 정도 크면 수유 대신 밥을 먹음 → 주행 중 그 시각 20분 전에 휴게소 제안. 비면 안 함 */
    val mealTimes: List<LocalTime> = emptyList(),
) {
    init {
        require(ageMonths in 0..144) { "ageMonths out of range" }
        require(feedIntervalMin in 30..600) { "feedIntervalMin out of range" }
        require(carSeatReclineAllowance in 0..10) { "carSeatReclineAllowance out of range" }
    }
}

/** 휴대폰에서 고른 자리에 앉는 사람 종류 */
enum class PlannedKind { ADULT, SENIOR, BOOSTER, CAR_SEAT }

/**
 * 휴대폰 2D 배치의 자리 하나. foreAft · backrest 는 휴대폰 AI 자세 (0..100, 앞뒤 100 = 맨 앞, 등받이 100 = 가장 세움).
 * 자세는 카시트 규칙 단계와 겹치지 않는 축에만 씀 (SeatPlanner).
 */
data class PlannedSeat(
    val seat: SeatId,
    val label: String,
    val kind: PlannedKind,
    val foreAft: Int? = null,
    val backrest: Int? = null,
)

/** 짐 종류와 대략적인 부피(L). 차종·제품마다 다르므로 근사치. */
enum class LuggageType(val labelKo: String, val liters: Int) {
    STROLLER("유모차", 130),
    COMPACT_STROLLER("휴대용 유모차", 60),
    SUITCASE("캐리어", 90),
    CARRY_ON("기내용 캐리어", 45),
    BABY_BAG("아기 짐가방", 30),
    PORTABLE_CRIB("휴대용 침대", 70),
    COOLER("아이스박스", 35),
    OTHER("기타 짐", 30),
}

/**
 * 이번 여정 입력. Figma 1-A 탑승 카드의 6개 타일과 같은 구성.
 * adults 는 운전자를 뺀 어른 수.
 */
data class TripRequest(
    val destinationName: String? = null,
    val seniors: Int = 0,
    val adults: Int = 1,
    val children: Int = 0,
    val infants: Int = 1,
    val luggage: Map<LuggageType, Int> = emptyMap(),
    val caregiverNextToBaby: Boolean = true,
    val tightParkingSide: Side? = null,
    /** 휴대폰이 적어 둔 크기로 셈한 짐 부피 합(L, 빈틈 계산 전). 없으면 개수 × LuggageType.liters 로 셈. */
    val measuredLuggageLiters: Int? = null,
    /** 휴대폰이 알려 준 뒤보기 카시트 아이가 있음. 프로필 방향과 상관없이 후방 카시트 공간 규칙을 적용함. */
    val rearFacingSeat: Boolean = false,
    /** 휴대폰 2D 배치에서 보호자가 직접 고른 자리 (+ 휴대폰 AI 자세). 있으면 SeatPlanner 가 이 자리를 따름 */
    val seatPlan: List<PlannedSeat> = emptyList(),
) {
    init {
        require(seniors >= 0 && adults >= 0 && children >= 0 && infants >= 0) { "negative passenger count" }
        require(luggage.values.all { it >= 0 }) { "negative luggage count" }
    }

    /** 운전자 제외 탑승 인원 */
    val passengers: Int get() = seniors + adults + children + infants

    val luggageCount: Int get() = luggage.values.sum()

    val soloCaregiver: Boolean get() = seniors + adults == 0
}

enum class BabyState(val labelKo: String) {
    AWAKE("깨어 있음"),
    DROWSY("졸림"),
    ASLEEP("자는 중"),
    CRYING("울음"),
    HUNGRY("배고파함"),
    UNKNOWN("확인 안 됨"),
}

/** 아기 상태가 어디서 왔는지. 화면과 로그에 출처를 같이 보여 줌. */
enum class StateSource(val labelKo: String) {
    BUTTON("보호자 버튼"),
    GLEO_VOICE("Gleo 음성"),
    BABY_MONITOR("아기 모니터"),
    SIMULATION("시뮬레이션"),
    PHONE("보호자 휴대폰"),
}

enum class DrivingState { PARKED, DRIVING, UNKNOWN }

data class GeoPoint(val lat: Double, val lng: Double)

/** 내비(NaviHelper)에서 받은 현재 경로 정보 */
data class RouteSnapshot(
    val destinationName: String?,
    val destination: GeoPoint?,
    val remainSeconds: Long,
    val remainMeters: Long?,
    val receivedAt: LocalDateTime,
)

/** 지난 여정 기록 (도착 시간 보정 학습용) */
data class TripRecord(
    val predictedMinutes: Int,
    val actualMinutes: Int,
    val naviMinutes: Int,
)
