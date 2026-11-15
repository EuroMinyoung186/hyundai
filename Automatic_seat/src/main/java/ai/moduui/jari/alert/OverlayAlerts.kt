package ai.moduui.jari.alert

import ai.moduui.jari.ui.MainActivity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 내비 위에 뜨는 알림 카드 (실제 알람처럼).
 * Pleos 는 시스템 앱 알림만 헤드업으로 띄우고 일반 앱 알림은 알림 센터에만 넣음
 * (NOTIFICATION_INJECTOR isSystemImportantNotification, 11/14 확인) → 앱이 직접 위에 그림.
 * 다른 앱 위에 그리기(SYSTEM_ALERT_WINDOW) 허용이 없으면 시스템 알림만 남김.
 * 색은 앱(휴대폰 앱과 같은 밤 톤): 카드 #14181D · 선 #262D35 · 강조 #3EC5F0.
 */
class OverlayAlerts(
    context: Context,
    private val fallback: AlertSink,
    private val onAction: (String) -> Unit,
) : AlertSink {
    private val app = context.applicationContext
    private val wm = app.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val shown = mutableMapOf<AlertKind, View>()
    private val lps = mutableMapOf<AlertKind, WindowManager.LayoutParams>()

    init {
        // 알림 칸은 가장 최근 것 하나만: 작은 알림이 새로 뜨면 카드를 내림
        TopRightStack.onClaim { owner -> if (owner !== this) main.post { for (k in shown.keys.toList()) remove(k) } }
    }

    /** 내비 오른쪽 패널을 펴거나 접거나 작은 알림이 바뀌었을 때: 떠 있는 카드를 다시 쌓고 맨 위로 올림 */
    fun relayout() = main.post {
        stack()
        for ((kind, v) in shown.toMap()) {
            val lp = lps[kind] ?: continue
            runCatching { wm.removeView(v); wm.addView(v, lp) }
        }
    }

    /**
     * 알림은 늘 오른쪽 위 한 줄에 (11/15 사용자: "오른쪽 정렬 · 안 겹치게 · 최신 것만").
     * 아기 상태 · 출발 질문 아래 알림 칸에, 다른 칸과 같은 오른쪽 끝 · 같은 폭으로.
     */
    private fun place(kind: AlertKind, lp: WindowManager.LayoutParams) {
        lp.gravity = Gravity.TOP or Gravity.END
        lp.width = dp(TopRightStack.COL_W_DP)
        lp.x = dp(TopRightStack.RIGHT_DP)
    }

    /** 떠 있는 카드의 세로 자리를 정하고, 알림 칸의 아래 끝을 알림 (들를 곳 패널이 그 아래부터) */
    private fun stack() {
        var y = TopRightStack.slotTopPx(app.resources.displayMetrics.density)
        var bottom = 0
        for ((kind, v) in shown) {
            val lp = lps[kind] ?: continue
            place(kind, lp)
            lp.y = y
            bottom = y + (v.height.takeIf { it > 0 } ?: dp(if (kind == AlertKind.MUSIC) 90 else 170))
            y = bottom + dp(GAP)
        }
        // 카드가 알림 칸을 차지하고 있을 때만 아래 끝을 알림 (작은 알림이 차지했으면 그쪽이 알림)
        if (bottom > 0) TopRightStack.setSlotBottom(bottom)
        else if (TopRightStack.latest.value === this) TopRightStack.setSlotBottom(0)
    }

    /** 위 칸이 바뀌었을 때: 자리만 다시 */
    fun restackNow() = main.post { restack() }

    /** 카드 크기 · 개수가 바뀌면 자리만 다시 (맨 위로 올리지는 않음) */
    private fun restack() {
        stack()
        for ((kind, v) in shown) lps[kind]?.let { lp -> runCatching { wm.updateViewLayout(v, lp) } }
    }

    override fun show(alert: CarAlert) {
        // 알림 센터 기록은 늘 남김 (나중에 다시 볼 수 있게)
        fallback.show(alert)
        main.post { showCard(alert) }
    }

    override fun cancel(kind: AlertKind) {
        fallback.cancel(kind)
        main.post { remove(kind) }
    }

    private fun showCard(alert: CarAlert) {
        if (!Settings.canDrawOverlays(app)) {
            Log.i(TAG, "overlay not allowed, notification only")
            return
        }
        // 휴게소 제안은 오른쪽 '경유 가능한 곳' 패널(NaviSidePanel)이 보여 줌 → 카드는 띄우지 않음
        // (패널이 뜨기 전에 카드가 먼저 떠서 둘이 같이 보이던 문제, 11/15)
        if (alert.kind == AlertKind.REST) return
        // 알림 칸은 가장 최근 것 하나만 (11/15 사용자): 예전 카드는 내리고, 작은 알림도 내리게 칸을 차지함
        for (k in shown.keys.toList()) remove(k)
        TopRightStack.claim(this)
        val compact = alert.kind == AlertKind.MUSIC
        val card = build(alert, compact)
        val lp = WindowManager.LayoutParams(
            dp(TopRightStack.COL_W_DP), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        place(alert.kind, lp)
        shown[alert.kind] = card
        lps[alert.kind] = lp
        stack()
        runCatching { wm.addView(card, lp) }
            .onSuccess {
                // 그려져 높이를 알면 아래 카드들을 그만큼 내림
                card.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom -> if (bottom - top != oldBottom - oldTop) main.post { restack() } }
                // 노래 카드는 노래가 멈출 때까지 둠 (컨트롤러가 cancel)
                if (!compact) main.postDelayed({ remove(alert.kind, card) }, if (alert.kind == AlertKind.PLAN) PLAN_SHOW_MS else SHOW_MS)
            }
            .onFailure {
                Log.w(TAG, "addView failed", it)
                shown.remove(alert.kind)
                lps.remove(alert.kind)
            }
    }

    private fun openApp() {
        // 다른 앱 위에 그리기 허용이 있으면 백그라운드에서도 화면을 열 수 있음
        runCatching {
            app.startActivity(
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }.onFailure { Log.w(TAG, "open app failed", it) }
    }

    private fun remove(kind: AlertKind, only: View? = null) {
        val v = shown[kind] ?: return
        if (only != null && only !== v) return
        shown.remove(kind)
        lps.remove(kind)
        runCatching { wm.removeView(v) }
        restack() // 아래 카드를 위로 올림
    }

    private fun build(alert: CarAlert, compact: Boolean): View {
        if (compact) return buildCompact(alert)
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(CARD)
                setStroke(dp(2), ACCENT)
            }
            elevation = dp(12).toFloat()
        }
        root.addView(TextView(app).apply {
            text = "Baby Seat"
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        root.addView(TextView(app).apply {
            text = alert.title
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(4))
        })
        root.addView(TextView(app).apply {
            text = alert.text
            setTextColor(SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setLineSpacing(0f, 1.15f)
        })
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        // 출발 알림(수유 · 밥 시간)은 고를 것이 많아 4개까지, 글씨는 조금 작게
        val max = if (alert.kind == AlertKind.PLAN) 5 else 3
        val shownButtons = alert.buttons.take(max)
        shownButtons.forEachIndexed { i, b ->
            // 수유 시각 고르기 카드는 [확인]만 강조
            val picker = alert.buttons.any { it.id == AlertActions.FEED_PICK_OK }
            val primary = if (picker) b.id == AlertActions.FEED_PICK_OK else i == 0 && b.id !in QUIET_BUTTONS
            row.addView(button(alert, b, primary, if (shownButtons.size > 3) 17f else 20f), LinearLayout.LayoutParams(0, dp(64), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        if (alert.buttons.isNotEmpty()) root.addView(row)
        return root
    }

    /** 노래처럼 계속 떠 있는 상태: 한 줄 + 오른쪽 버튼 하나 */
    private fun buildCompact(alert: CarAlert): View {
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(CARD)
                setStroke(dp(2), ACCENT)
            }
            elevation = dp(10).toFloat()
        }
        val texts = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(app).apply {
            text = alert.title
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
        })
        if (alert.text.isNotBlank()) texts.addView(TextView(app).apply {
            text = alert.text
            setTextColor(SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
        root.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        alert.buttons.firstOrNull()?.let { b ->
            root.addView(button(alert, b, primary = false, sp = 22f), LinearLayout.LayoutParams(dp(130), dp(68)).apply { marginStart = dp(12) })
        }
        return root
    }

    private fun button(alert: CarAlert, b: AlertButton, primary: Boolean, sp: Float) = TextView(app).apply {
        text = b.label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primary) ON_ACCENT else TEXT)
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            if (primary) {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(ACCENT, ACCENT_DEEP)
            } else {
                setColor(CHIP)
                setStroke(dp(1), LINE)
            }
        }
        setOnClickListener {
            remove(alert.kind)
            fallback.cancel(alert.kind)
            if (b.id == AlertActions.ARRIVE_OPEN) openApp()
            onAction(b.id)
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), app.resources.displayMetrics).toInt()

    companion object {
        /** 알림 카드 폭 (11/15 사용자: "크게 띄우지 마 조그맣게", 예전 720) */
        const val CARD_W = 460
        private const val TAG = "OverlayAlerts"
        private const val SHOW_MS = 60_000L

        /** 쌓인 카드 사이 간격 (dp) */
        private const val GAP = 12
        /** 강조색을 쓰지 않는 버튼 (닫기 · 확인 쪽) */
        private val QUIET_BUTTONS = setOf(AlertActions.REST_DISMISS, AlertActions.CARE_DISMISS, AlertActions.ARRIVE_OK, AlertActions.MUSIC_OFF, AlertActions.FEED_KEEP, AlertActions.MEAL_NONE)

        /** 출발 알림은 고를 시간을 넉넉히 */
        private const val PLAN_SHOW_MS = 120_000L
        private val CARD = Color.parseColor("#F014181D")
        private val CHIP = Color.parseColor("#1C2228")
        private val LINE = Color.parseColor("#262D35")
        private val TEXT = Color.parseColor("#F2F5F7")
        private val SUB = Color.parseColor("#B4BDC7")
        private val ACCENT = Color.parseColor("#3EC5F0")
        private val ACCENT_DEEP = Color.parseColor("#1A9FD6")
        private val ON_ACCENT = Color.parseColor("#06222E")
    }
}
