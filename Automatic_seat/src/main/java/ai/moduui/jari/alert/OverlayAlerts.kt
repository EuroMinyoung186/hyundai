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
        remove(alert.kind)
        val compact = alert.kind == AlertKind.MUSIC
        val card = build(alert, compact)
        val lp = WindowManager.LayoutParams(
            dp(if (compact) 560 else 720), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 종류마다 자리를 나눠 겹치지 않게. 내비 오른쪽 위 [2D] 버튼(아래 끝 약 120dp)은 가리지 않음.
            // 노래는 오른쪽 아래, 내비 음량 · 설정 버튼 왼쪽에 작게.
            when (alert.kind) {
                AlertKind.MUSIC -> {
                    gravity = Gravity.BOTTOM or Gravity.END
                    x = dp(170)
                    y = dp(24)
                }
                else -> {
                    gravity = Gravity.TOP or Gravity.END
                    x = dp(24)
                    y = dp(if (alert.kind == AlertKind.CARE) TOP_Y + 310 else TOP_Y)
                }
            }
        }
        runCatching { wm.addView(card, lp) }
            .onSuccess {
                shown[alert.kind] = card
                // 노래 카드는 노래가 멈출 때까지 둠 (컨트롤러가 cancel)
                if (!compact) main.postDelayed({ remove(alert.kind, card) }, if (alert.kind == AlertKind.PLAN) PLAN_SHOW_MS else SHOW_MS)
            }
            .onFailure { Log.w(TAG, "addView failed", it) }
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
        runCatching { wm.removeView(v) }
    }

    private fun build(alert: CarAlert, compact: Boolean): View {
        if (compact) return buildCompact(alert)
        val root = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(30), dp(24), dp(30), dp(24))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(CARD)
                setStroke(dp(2), ACCENT)
            }
            elevation = dp(12).toFloat()
        }
        root.addView(TextView(app).apply {
            text = "모두의 자리"
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        })
        root.addView(TextView(app).apply {
            text = alert.title
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(8))
        })
        root.addView(TextView(app).apply {
            text = alert.text
            setTextColor(SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setLineSpacing(0f, 1.2f)
        })
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
        }
        // 출발 알림(수유 · 밥 시간)은 고를 것이 많아 4개까지, 글씨는 조금 작게
        val max = if (alert.kind == AlertKind.PLAN) 4 else 3
        val shownButtons = alert.buttons.take(max)
        shownButtons.forEachIndexed { i, b ->
            val primary = i == 0 && b.id !in QUIET_BUTTONS
            row.addView(button(alert, b, primary, if (shownButtons.size > 3) 21f else 24f), LinearLayout.LayoutParams(0, dp(84), 1f).apply { if (i > 0) marginStart = dp(10) })
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
        private const val TAG = "OverlayAlerts"
        private const val SHOW_MS = 60_000L
        /** 위쪽 카드의 시작 높이. 내비 [2D] 버튼 아래 */
        private const val TOP_Y = 130
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
