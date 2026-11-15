package ai.moduui.jari.alert

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 내비 위 오른쪽 위 알림들이 겹치지 않게 쌓는 순서 (11/15 사용자: "알림들 안 겹치게").
 *  1) 출발 질문 카드 (StartAskCard) — 맨 위
 *  2) 작은 상황 알림 (NaviSidePanel 의 note)
 *  3) 버튼 알림 카드들 (OverlayAlerts, 먼저 뜬 것이 위)
 * 위 칸이 뜨거나 크기가 바뀌면 아래 칸이 그 아래 끝(px)부터 다시 놓임.
 */
object TopRightStack {
    /** 출발 질문 카드의 아래 끝 (px, 화면 위에서). 없으면 0 */
    @Volatile var askBottomPx = 0
        private set

    private val _changed = MutableStateFlow(0)

    /** 위 칸이 바뀔 때마다 늘어나는 값 (아래 칸이 다시 놓을 신호) */
    val changed: StateFlow<Int> = _changed

    fun setAskBottom(px: Int) {
        if (askBottomPx == px) return
        askBottomPx = px
        _changed.value++
    }

    /** 쌓인 칸 사이 간격 (dp) */
    const val GAP_DP = 12
}
