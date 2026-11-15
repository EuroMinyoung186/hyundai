package ai.moduui.jari.debug

import ai.moduui.core.model.DrivingState
import ai.moduui.jari.AppGraph
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 시연 전용 (debug): 내비 화면 위 시연 지도(NaviMapLayer)를 켜고 끄며 장면을 보여 줌.
 *
 *  1. 출발(D) → 내비 + 시연 지도 (실제 지도 위 파란 화살표가 도로를 따라 감)
 *  2. 들를 곳 제안(수유 30분 전 · 기저귀 · 울음) → 내비 + 오른쪽 패널(경유 가능한 곳)
 *  3. [경유] · Gleo 로 고름 → 시연 지도로 바로 이어 달림 + 오른쪽 위 "경유로 바꿨어요" 알림 (그 휴게소까지 가서 P단 정차)
 *  4. D 로 바꾸면 다시 출발
 *  5. 그 밖의 일(잠듦 등) → 오른쪽 위 알림 [infoMs] (알림을 눌러 상세를 열면 닫을 때까지)
 *  6. 도착 → 시연 지도를 걷고 내비
 *
 *   adb shell am broadcast ... --es cmd demo --es on true [--es reroute 8] [--es info 7]
 */
object DemoDirector {
    private var job: Job? = null
    private var sceneJob: Job? = null
    private var busy = false
    /** 내비에 들를 곳 패널을 띄워 두고 고르기를 기다리는 중 */
    private var restShown = false
    var rerouteMs = 8_000L
    var infoMs = 7_000L

    fun start(context: Context, graph: AppGraph) {
        if (job != null) return
        val app = context.applicationContext
        val c = graph.controller
        val panel = graph.naviPanel
        // 지도 창은 먼저 만들어 둠 → 나중에 뜨는 패널 · 알림이 늘 그 위에 옴
        NaviMapLayer.ensure(app, c)
        job = graph.scope.launch {
            // 1~4, 6: 컨트롤러 상태
            launch {
                var wasDriving = false
                var lastOffer: LocalDateTime? = null
                var lastAdded: String? = null
                var wasArrived = false
                c.ui.collect { ui ->
                    val driving = ui.driving.state == DrivingState.DRIVING || ui.driving.gear == "D"
                    val o = ui.restStops
                    when {
                        // 도착 → 내비
                        ui.arrived && !wasArrived -> scene(graph) {
                            NaviMapLayer.rerouted.value = "${DemoDrive.progress.value?.destination ?: "목적지"}에 도착했어요"
                        }
                        // 새 들를 곳 제안 → 내비 + 오른쪽 패널 (고르거나 닫을 때까지 내비에 머묾)
                        o != null && o.addedName == null && o.candidates.isNotEmpty() && o.createdAt != lastOffer -> {
                            lastOffer = o.createdAt
                            restShown = true
                            sceneJob?.cancel()
                            toNavi(app, graph)
                        }
                        // 경유지를 골랐음 → 내비가 다시 찾는 모습 → 시뮬레이션 (그 휴게소에서 정차)
                        o?.addedName != null && o.addedName != lastAdded -> {
                            lastAdded = o.addedName
                            restShown = false
                            o.candidates.firstOrNull { it.restArea.name == o.addedName }?.let { DemoDrive.stopAt(c, it.restArea.name, it.restArea.location) }
                            val added = o.addedName
                            scene(graph) {
                                // 내비(NaviHelper addWaypoint)가 경유지를 넣음 → 바로 지도로 이어 달리고, 오른쪽 위 짧은 알림만
                                toSim(app, graph)
                                panel.toast.value = ai.moduui.jari.alert.InfoEvent(
                                    "☕", "$added 경유로 바꿨어요",
                                    listOf("내비 경유지에 넣었어요", "도착하면 P로 세워 드려요"),
                                    java.time.LocalTime.now(),
                                )
                                delay(3_000)
                            }
                        }
                        // 제안을 닫았음 → 시뮬레이션
                        restShown && o == null -> {
                            restShown = false
                            scene(graph) {
                                delay(1_500)
                                toSim(app, graph)
                            }
                        }
                        // 출발 · 다시 출발 → 시뮬레이션
                        driving && !wasDriving && !busy && !restShown -> toSim(app, graph)
                    }
                    if (o == null) lastAdded = null
                    wasDriving = driving
                    wasArrived = ui.arrived
                }
            }
            // 경유지에 도착해 섰을 때: 오른쪽 위 알림으로 알려 줌
            launch {
                var was = false
                DemoDrive.progress.collect { pr ->
                    val now = pr?.stopped == true
                    if (now && !was) {
                        panel.toast.value = ai.moduui.jari.alert.InfoEvent(
                            "☕", "${pr?.stopName ?: "휴게소"} 도착 · 정차 중",
                            listOf("기어 P · 수유가 끝나면 [수유했어요]를 눌러 주세요", "D로 바꾸면 다시 출발해요"),
                            java.time.LocalTime.now(),
                            sticky = true,
                        )
                    } else if (!now && was) {
                        // 다시 출발하면 정차 알림을 걷음
                        if (panel.toast.value?.sticky == true) panel.toast.value = null
                    }
                    was = now
                }
            }
            // 5: 그 밖의 일 → 내비 + 오른쪽 위 알림
            launch {
                panel.toast.collect { t ->
                    // 정차 알림은 시연 지도 위에 그대로 둠 (장면 전환 없음)
                    if (t == null || t.sticky || busy || restShown) return@collect
                    scene(graph) {
                        toNavi(app, graph)
                        delay(infoMs)
                        // 알림을 눌러 상세를 보고 있으면 닫을 때까지 기다림
                        while (panel.detail.value != null) delay(500)
                        toSim(app, graph)
                    }
                }
            }
        }
        Log.i(TAG, "demo director on")
    }

    fun stop() {
        job?.cancel()
        job = null
        sceneJob?.cancel()
        busy = false
        restShown = false
        NaviMapLayer.hide()
    }

    /** 장면 하나 (앞 장면은 끊음). 끝나면 다른 이벤트를 다시 받음 */
    private fun scene(graph: AppGraph, block: suspend () -> Unit) {
        sceneJob?.cancel()
        busy = true
        sceneJob = graph.scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }

    /** 주행 장면: 내비를 앞에 두고 그 위에 시연 지도 */
    private fun toSim(context: Context, graph: AppGraph) {
        toNavi(context, graph)
        NaviMapLayer.show()
    }

    private fun toNavi(context: Context, graph: AppGraph) {
        // NaviHelper Foreground 요청은 내비가 받기만 하고 앞으로 오지 않아서(11/15 확인), 내비 앱을 직접 엶
        val opened = runCatching {
            val i = context.packageManager.getLaunchIntentForPackage(NAVI_PKG) ?: return@runCatching false
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            true
        }.onFailure { Log.w(TAG, "toNavi failed", it) }.getOrDefault(false)
        if (!opened) graph.controller.showNavi()
    }

    private const val TAG = "DemoDirector"
    private const val NAVI_PKG = "ai.umos.maps.android.navigation.app"
}
