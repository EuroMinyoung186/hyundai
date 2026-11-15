package ai.moduui.jari.debug

import ai.moduui.core.model.BabyState
import ai.moduui.core.model.GeoPoint
import ai.moduui.core.model.LuggageType
import ai.moduui.core.model.SeatId
import ai.moduui.core.model.StateSource
import ai.moduui.core.model.TripRequest
import ai.moduui.jari.ModuuiApp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.time.LocalDateTime
import kotlinx.coroutines.launch

/**
 * 디버그 빌드에만 들어가는 테스트용 수신기 (release 에는 없음 → 불필요한 외부 노출 금지 정책 준수).
 *
 * 차량 상태(기어 · 속도 · 문 · 착좌)는 Pleos Virtual Console 이 실제 VHAL 로 바꾸므로 여기서 다루지 않음.
 * 여기서는 차에 없는 입력만 받음: 아기 상태(아기 모니터) · 수유 기록 · 여정(Gleo 와 같은 내용) · 내비 경로 요청 · 도착.
 * 외부 시뮬레이터 Automatic_seat/tools/sim/everyseat-sim.ps1 이 adb 로 보냄.
 *
 *  adb shell am broadcast -a ai.moduui.jari.debug.CMD -n ai.moduui.jari/.debug.DebugCommandReceiver \
 *      --es cmd baby --es state ASLEEP --es source monitor
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as ModuuiApp).graph
        val c = graph.controller
        val cmd = intent.getStringExtra("cmd") ?: return
        fun s(key: String) = intent.getStringExtra(key)
        fun n(key: String) = s(key)?.toIntOrNull() ?: 0
        Log.i(TAG, "cmd=$cmd extras=${intent.extras?.keySet()?.joinToString()}")
        when (cmd) {
            "baby" -> {
                val state = runCatching { BabyState.valueOf(s("state") ?: "") }.getOrNull() ?: return
                val source = when (s("source")) {
                    "monitor" -> StateSource.BABY_MONITOR
                    "gleo" -> StateSource.GLEO_VOICE
                    "button" -> StateSource.BUTTON
                    else -> StateSource.SIMULATION
                }
                c.reportBaby(state, source)
            }
            "feed" -> {
                val ago = s("minutesAgo")?.toLongOrNull() ?: 0
                c.logFeed(graph.clock.now().minusMinutes(ago))
            }
            // Gleo PREPARE_DEPARTURE 와 같은 내용 (adb 로 JSON 을 넘기기 번거로워 값별로 받음)
            "trip" -> c.planTrip(
                TripRequest(
                    destinationName = s("destination"),
                    seniors = n("seniors"),
                    adults = n("adults"),
                    children = n("children"),
                    infants = s("infants")?.toIntOrNull() ?: 1,
                    luggage = mapOf(
                        LuggageType.STROLLER to n("strollers"),
                        LuggageType.SUITCASE to n("suitcases"),
                        LuggageType.BABY_BAG to n("babyBags"),
                    ).filterValues { it > 0 },
                ),
                "Gleo",
            )
            "naviRoute" -> {
                val lat = s("lat")?.toDoubleOrNull() ?: return
                val lng = s("lng")?.toDoubleOrNull() ?: return
                c.requestNaviRoute(s("name") ?: "목적지", GeoPoint(lat, lng))
            }
            // 휴대폰 [출발 준비]와 같음: 카운트다운 뒤 좌석을 맞춤
            "prepare" -> c.confirmDeparture()
            // 화면 버튼과 같은 동작 (시나리오를 손 없이 돌리려고)
            "rest" -> graph.scope.launch { c.addRestStop(n("index")) }
            "drive" -> c.showDrive()
            "comfort" -> c.moveCaregiverTowardBaby()
            "undoComfort" -> c.undoComfort()
            "remain" -> c.simulateRemaining(n("minutes"))
            "allowance" -> c.setCarSeatAllowance(n("value"))
            "llm" -> LlmProbe.run(context.applicationContext, s("mode") == "server", s("clientId"), s("clientSecret"))
            "seatCaps" -> SeatProbe.caps(context.applicationContext)
            "seatMove" -> SeatProbe.move(context.applicationContext, s("area"), s("axis"), s("dir"), s("ms")?.toLongOrNull() ?: 1_500)
            "naviCancel" -> c.cancelNaviRoute()
            "arrive" -> c.simulateArrival(s("name"))
            // 시연 화면 전환기: 시뮬레이션 지도 ↔ 내비 (on true|false, reroute/info 초)
            "demoReset" -> DemoReset.reset(context, graph)
            "demo" -> if (s("on") == "false") DemoDirector.stop() else {
                s("reroute")?.toLongOrNull()?.let { DemoDirector.rerouteMs = it * 1000 }
                s("info")?.toLongOrNull()?.let { DemoDirector.infoMs = it * 1000 }
                DemoDirector.start(context, graph)
            }
            // 실제 도로를 따라 앱 지도 위 차를 움직임 (에뮬레이터 내비 위치가 고정이라)
            "demoDrive" -> if (s("stop") == "true") DemoDrive.stop(c) else DemoDrive.start(
                graph.scope, c,
                kmh = s("kmh")?.toDoubleOrNull() ?: 100.0,
                speedup = s("x")?.toDoubleOrNull() ?: 10.0,
                fromKm = s("from")?.toDoubleOrNull() ?: 0.0,
            )
            // 시뮬레이션 모드(Pleos 차량 서비스가 없는 환경)에서만 쓰는 가짜 차량 조작
            "gear" -> graph.fakeDriving?.setGear(s("value") ?: "P")
            "speed" -> s("kmh")?.toFloatOrNull()?.let { graph.fakeDriving?.setSpeed(it) }
            "occupied" -> {
                val seat = runCatching { SeatId.valueOf(s("seat") ?: "") }.getOrNull() ?: return
                graph.fakeVehicle?.setOccupied(seat, s("value")?.toBooleanStrictOrNull())
            }
            "door" -> {
                val seat = runCatching { SeatId.valueOf(s("seat") ?: "") }.getOrNull() ?: return
                graph.fakeVehicle?.setDoorOpen(seat, s("open") == "true")
            }
            "forceSim" -> graph.setForceSimulation(s("value") != "false")
            // 카메라 옵션 켜고 끄기 · 기저귀 알림 · 알림 [적용] (시험용)
            "camera" -> c.updateProfile(c.ui.value.profile.copy(cameraWatch = s("value") != "false"))
            // 휴대폰 "똥 쌌어요" 버튼과 같은 메시지 (type=baby, state 없음, NEARBY 기저귀 갈 곳)
            "diaper" -> c.onPhoneBaby(null, s("label") ?: "똥 쌌어요", "💩", listOf(ai.moduui.jari.control.CareAct("NEARBY", s("place") ?: "기저귀 갈 곳")))
            "careApply" -> c.applyCare()
            "stopMusic" -> c.stopMusic()
            "clock" -> {
                s("speed")?.toIntOrNull()?.let { graph.clock.setSpeed(it) }
                s("at")?.let { runCatching { graph.clock.jumpTo(LocalDateTime.parse(it)) } }
            }
            else -> Log.w(TAG, "unknown cmd $cmd")
        }
    }

    companion object {
        private const val TAG = "ModuuiDebug"
    }
}
