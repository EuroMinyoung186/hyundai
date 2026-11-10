package ai.moduui.core.seat

import ai.moduui.core.model.BabyProfile
import ai.moduui.core.model.CarSeatDirection
import ai.moduui.core.model.LuggageType
import ai.moduui.core.model.SeatId
import ai.moduui.core.model.Side
import ai.moduui.core.model.TripRequest
import kotlin.math.roundToInt

/**
 * 카시트 좌석·짐 공간 판단 엔진.
 *
 * 안전과 관련된 판단이라 LLM을 쓰지 않고 규칙으로 처리함. 같은 입력이면 항상 같은 결과.
 * 6석(2-2-2) 실내를 기준으로 하며, 트렁크 용량은 7인승 SUV 근사치임 (실차 값으로 교체 대상).
 */
object SeatPlanner {

    /** 짐은 빈틈없이 쌓이지 않으므로 트렁크 용량의 75%만 쓸 수 있다고 봄 */
    const val PACKING_EFFICIENCY = 0.75

    fun luggageLiters(luggage: Map<LuggageType, Int>): Int =
        (luggage.entries.sumOf { (type, n) -> type.liters * n } / PACKING_EFFICIENCY).roundToInt()

    fun plan(profile: BabyProfile, trip: TripRequest): SeatPlan {
        val notes = mutableListOf<String>()
        val seats = linkedMapOf<SeatId, Occupant>(SeatId.R1_LEFT to Occupant.DRIVER)
        fun free(seat: SeatId) = seat !in seats

        // 1) 카시트: 인도 쪽(오른쪽) 2열 우선. 오른쪽 주차 공간이 좁으면 왼쪽.
        val firstCarSeat = if (trip.tightParkingSide == Side.RIGHT) SeatId.R2_LEFT else SeatId.R2_RIGHT
        val carSeatOrder = listOf(firstCarSeat, firstCarSeat.otherSideSameRow(), SeatId.R3_RIGHT, SeatId.R3_LEFT)
        repeat(trip.infants) {
            val seat = carSeatOrder.firstOrNull(::free)
            if (seat == null) notes += "카시트를 달 좌석이 부족해요" else seats[seat] = Occupant.INFANT_CAR_SEAT
        }

        var adultsLeft = trip.adults
        var seniorsLeft = trip.seniors

        // 2) 보호자는 아기 옆자리
        val primaryCarSeat = seats.entries.firstOrNull { it.value == Occupant.INFANT_CAR_SEAT }?.key
        if (trip.caregiverNextToBaby && primaryCarSeat != null && (adultsLeft + seniorsLeft) > 0) {
            val beside = primaryCarSeat.otherSideSameRow()
            if (free(beside)) {
                seats[beside] = Occupant.CAREGIVER
                if (adultsLeft > 0) adultsLeft-- else seniorsLeft--
            }
        }

        // 3) 부스터 아이: 2열 → 3열
        repeat(trip.children) {
            val seat = listOf(SeatId.R2_LEFT, SeatId.R2_RIGHT, SeatId.R3_LEFT, SeatId.R3_RIGHT).firstOrNull(::free)
            if (seat == null) notes += "아이가 앉을 좌석이 부족해요" else seats[seat] = Occupant.CHILD_BOOSTER
        }

        // 4) 어르신은 타고 내리기 쉬운 조수석 우선, 그다음 2열 → 3열
        val adultOrder = listOf(SeatId.R1_RIGHT, SeatId.R2_LEFT, SeatId.R2_RIGHT, SeatId.R3_LEFT, SeatId.R3_RIGHT)
        repeat(seniorsLeft) {
            val seat = adultOrder.firstOrNull(::free)
            if (seat == null) notes += "어르신이 앉을 좌석이 부족해요" else seats[seat] = Occupant.SENIOR
        }
        repeat(adultsLeft) {
            val seat = adultOrder.firstOrNull(::free)
            if (seat == null) notes += "어른이 앉을 좌석이 부족해요" else seats[seat] = Occupant.ADULT
        }

        // 5) 트렁크: 사람이 앉지 않은 3열만 접을 수 있음. 3열 오른쪽에 앉은 사람은 왼쪽이 비면 옮김.
        val liters = luggageLiters(trip.luggage)
        if (!free(SeatId.R3_RIGHT) && free(SeatId.R3_LEFT) && liters > TrunkMode.ALL_UP.usableLiters) {
            seats[SeatId.R3_LEFT] = seats.remove(SeatId.R3_RIGHT)!!
        }
        val candidates = buildList {
            add(TrunkMode.ALL_UP)
            if (free(SeatId.R3_RIGHT)) add(TrunkMode.R3_RIGHT_FOLDED)
            if (free(SeatId.R3_RIGHT) && free(SeatId.R3_LEFT)) add(TrunkMode.R3_ALL_FOLDED)
        }
        val trunk = candidates.firstOrNull { liters <= it.usableLiters } ?: candidates.last()
        val overflow = (liters - trunk.usableLiters).coerceAtLeast(0)

        // 6) 실행 단계 만들기
        val steps = mutableListOf<SeatStep>()
        val thirdRowUsed = !free(SeatId.R3_LEFT) || !free(SeatId.R3_RIGHT)
        if (thirdRowUsed) {
            listOf(SeatId.R3_LEFT, SeatId.R3_RIGHT).filterNot(::free).forEach {
                steps += SeatStep(it, SeatAction.Unfold, Executor.GUIDE, "3열에 ${seats[it]!!.labelKo} 탑승")
            }
        }
        when (trunk) {
            TrunkMode.ALL_UP -> Unit
            TrunkMode.R3_RIGHT_FOLDED ->
                steps += SeatStep(SeatId.R3_RIGHT, SeatAction.Fold, Executor.GUIDE, "짐 ${liters}L가 트렁크 ${TrunkMode.ALL_UP.usableLiters}L를 넘음")
            TrunkMode.R3_ALL_FOLDED -> listOf(SeatId.R3_LEFT, SeatId.R3_RIGHT).forEach {
                steps += SeatStep(it, SeatAction.Fold, Executor.GUIDE, "짐 ${liters}L, 3열 탑승 없음")
            }
        }

        for (carSeat in seats.filterValues { it == Occupant.INFANT_CAR_SEAT }.keys.sortedBy { it.ordinal }) {
            val behind = SeatId.entries.firstOrNull { it.row == carSeat.row + 1 && it.side == carSeat.side }
            val behindOccupied = behind != null && !free(behind)
            steps += SeatStep(
                carSeat,
                SeatAction.Move(SeatAxis.FORE_AFT, if (behindOccupied) 0.3f else 0f),
                Executor.SDK_CONTROL,
                if (behindOccupied) "뒷자리 다리 공간을 남기고 카시트 공간 확보" else "카시트 장착 공간 확보 (뒤로)",
            )
            steps += SeatStep(carSeat, SeatAction.Move(SeatAxis.BACKREST, 1f), Executor.SDK_CONTROL, "등받이를 세워야 카시트가 단단히 고정됨")
            steps += SeatStep(carSeat, SeatAction.Heat(0), Executor.SDK_CONTROL, "카시트 아래 열선은 끔")
            steps += SeatStep(carSeat, SeatAction.Vent(0), Executor.SDK_CONTROL, "카시트 아래 통풍은 끔")

            if (profile.seatDirection == CarSeatDirection.REAR_FACING) {
                val front = carSeat.seatInFront()
                when {
                    front == null -> Unit
                    front.isDriver -> notes += "운전석 뒤 후방 카시트: 운전석은 자동으로 움직이지 않아요. 운전 자세를 확인하세요"
                    else -> {
                        val occupied = !free(front)
                        steps += SeatStep(
                            front,
                            SeatAction.Move(SeatAxis.FORE_AFT, if (occupied) 0.6f else 0.9f),
                            Executor.SDK_CONTROL,
                            if (occupied) "후방 카시트 공간 확보 (${seats[front]!!.labelKo} 다리 공간 고려)" else "후방 카시트가 앞좌석에 닿지 않도록",
                        )
                    }
                }
            }
        }

        if (thirdRowUsed) {
            for (seat in listOf(SeatId.R2_LEFT, SeatId.R2_RIGHT)) {
                if (seats[seat] == Occupant.INFANT_CAR_SEAT) continue
                val behind = SeatId.entries.first { it.row == 3 && it.side == seat.side }
                if (!free(behind)) {
                    steps += SeatStep(seat, SeatAction.Move(SeatAxis.FORE_AFT, 0.6f), Executor.SDK_CONTROL, "3열 다리 공간 확보")
                }
            }
        }

        if (trip.soloCaregiver) notes += "혼자 태우시니 카시트 쪽 문 앞 공간을 먼저 확보하세요"
        if (trip.tightParkingSide != null) {
            val side = if (trip.tightParkingSide == Side.RIGHT) "오른쪽" else "왼쪽"
            notes += "$side 공간이 좁아요. 카시트를 반대쪽에 두고 반대쪽 문으로 태우세요"
        }
        if (overflow > 0) notes += "짐이 ${overflow}L 남아요. 루프박스를 쓰거나 짐을 줄여야 해요"

        return SeatPlan(
            assignments = seats.toMap(),
            trunkMode = trunk,
            luggageLiters = liters,
            overflowLiters = overflow,
            steps = steps,
            notesKo = notes,
        )
    }
}
