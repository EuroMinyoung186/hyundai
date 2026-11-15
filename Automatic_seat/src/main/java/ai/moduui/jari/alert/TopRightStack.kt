package ai.moduui.jari.alert

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 내비 위 오른쪽 한 줄 (11/15 사용자: "알림들 안 겹치게 · 오른쪽 정렬 · 최신 것만").
 * 모두 같은 오른쪽 끝(RIGHT_DP) · 같은 폭(COL_W_DP)으로, 위에서 아래로:
 *  1) 아기 상태 + 그 때문에 한 일 (NaviSidePanel 의 status) — 늘 맨 위, 작게
 *  2) 출발 질문 카드 (StartAskCard) — 물을 때만
 *  3) 알림 한 칸 — 버튼 알림 카드(OverlayAlerts) 또는 작은 알림(NaviSidePanel note) 중 가장 최근 것 하나만
 *  4) 들를 곳 패널 (NaviSidePanel panel) — 위 칸들 아래부터 화면 아래 끝까지
 * 위 칸이 뜨거나 크기가 바뀌면 아래 칸이 그 아래 끝(px)부터 다시 놓임.
 */
object TopRightStack {
    /** 오른쪽 끝에서 띄우는 거리 (dp). 내비 오른쪽 버튼 줄([2D] · 음량 · 설정) 왼쪽 */
    const val RIGHT_DP = 160

    /** 한 줄의 폭 (dp) */
    const val COL_W_DP = 640

    /** 맨 위 칸의 위 끝 (dp). 내비 위 상태 줄 아래 */
    const val TOP_DP = 92

    /** 쌓인 칸 사이 간격 (dp) */
    const val GAP_DP = 12

    /** 아기 상태 칸의 아래 끝 (px, 화면 위에서). 없으면 0 */
    @Volatile var statusBottomPx = 0
        private set

    /** 출발 질문 카드의 아래 끝 (px). 없으면 0 */
    @Volatile var askBottomPx = 0
        private set

    /** 알림 칸(카드 또는 작은 알림)의 아래 끝 (px). 없으면 0 */
    @Volatile var slotBottomPx = 0
        private set

    private val _changed = MutableStateFlow(0)

    /** 위 칸이 바뀔 때마다 늘어나는 값 (아래 칸이 다시 놓을 신호) */
    val changed: StateFlow<Int> = _changed

    fun setStatusBottom(px: Int) {
        if (statusBottomPx == px) return
        statusBottomPx = px
        _changed.value++
    }

    fun setAskBottom(px: Int) {
        if (askBottomPx == px) return
        askBottomPx = px
        _changed.value++
    }

    fun setSlotBottom(px: Int) {
        if (slotBottomPx == px) return
        slotBottomPx = px
        _changed.value++
    }

    /** 출발 질문 카드의 위 끝 (px): 아기 상태 칸 아래 */
    fun askTopPx(density: Float): Int = below(statusBottomPx, density)

    /** 알림 칸의 위 끝 (px): 아기 상태 · 출발 질문 아래 */
    fun slotTopPx(density: Float): Int = below(maxOf(statusBottomPx, askBottomPx), density)

    /** 들를 곳 패널의 위 끝 (px): 위 칸들 모두 아래 */
    fun panelTopPx(density: Float): Int = below(maxOf(statusBottomPx, askBottomPx, slotBottomPx), density)

    private fun below(px: Int, density: Float): Int =
        if (px > 0) px + (GAP_DP * density).toInt() else (TOP_DP * density).toInt()

    // ───── 알림 칸: 가장 최근 것 하나만 ─────

    private val _latest = MutableStateFlow<Any?>(null)

    /** 지금 알림 칸을 차지한 것. 다른 것이 새로 뜨면 바뀜 → 예전 것은 스스로 내려감 */
    val latest: StateFlow<Any?> = _latest

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Any) -> Unit>()

    /** 알림 칸을 누가 새로 차지했을 때 불림 (자기가 아니면 자기 알림을 내림) */
    fun onClaim(l: (Any) -> Unit) {
        listeners += l
    }

    /** 알림 칸을 차지함 (새 알림이 뜰 때) */
    fun claim(owner: Any) {
        _latest.value = owner
        listeners.forEach { it(owner) }
    }
}
