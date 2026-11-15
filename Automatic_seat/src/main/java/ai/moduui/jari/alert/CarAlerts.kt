package ai.moduui.jari.alert

import ai.moduui.jari.ModuuiApp
import ai.moduui.jari.R
import ai.moduui.jari.ui.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** 알림 종류. 같은 종류는 새 알림이 이전 것을 바꿈 */
enum class AlertKind(val id: Int) {
    /** 수유 · 기저귀 · 울음: 들를 휴게소 제안 */
    REST(101),
    /** 카메라가 본 아기 상태 (잠듦 · 깸) → [적용] */
    CARE(102),
    /** 앱이 노래를 트는 동안 작게 떠 있음 → [끄기] */
    MUSIC(103),
    /** 목적지 도착 · 문 잠금 때 뒷좌석 아기 확인 */
    ARRIVE(104),
    /** 길안내를 시작하면: Baby Seat 기능 사용? → [예]면 마지막 수유 시각 고르기 */
    PLAN(105),
}

/** 알림 버튼. id 는 [AlertActions] 의 값 */
data class AlertButton(val id: String, val label: String)

data class CarAlert(val kind: AlertKind, val title: String, val text: String, val buttons: List<AlertButton>)

/** 알림 버튼 id. 누르면 TripController.onAlertAction 으로 들어감 */
object AlertActions {
    fun rest(index: Int) = "rest:$index"
    const val REST_DISMISS = "rest:dismiss"
    /** 경로 앞쪽에 휴게소가 없을 때: 내비 장소 검색을 엶 */
    const val REST_SEARCH = "rest:search"
    const val CARE_APPLY = "care:apply"
    const val CARE_DISMISS = "care:dismiss"
    const val MUSIC_OFF = "music:off"
    /** 앱 도착 화면을 엶 (내비가 전체 화면이어도) */
    const val ARRIVE_OPEN = "arrive:open"
    const val ARRIVE_OK = "arrive:ok"
    /** 다음 수유를 지금부터 n분 뒤로 */
    fun feedIn(min: Int) = "feed:in:$min"
    /** 수유 간격으로 계산한 값 그대로 */
    const val FEED_KEEP = "feed:keep"
    /** 밥 시간을 지금부터 n분 뒤로 */
    fun mealIn(min: Int) = "meal:in:$min"
    const val MEAL_NONE = "meal:none"
    /** 출발 알림: Baby Seat 기능 사용 [예] / [아니요] (10초 뒤 아니요) */
    const val USE_YES = "use:yes"
    const val USE_NO = "use:no"
    /** 마지막 수유 시각 고르기: ±분, [확인] */
    fun feedPick(deltaMin: Int) = "feedpick:$deltaMin"
    const val FEED_PICK_OK = "feedpick:ok"
}

/** 차 화면 위로 뜨는 알림. 시험에서는 [None] */
interface AlertSink {
    fun show(alert: CarAlert)
    fun cancel(kind: AlertKind)

    object None : AlertSink {
        override fun show(alert: CarAlert) = Unit
        override fun cancel(kind: AlertKind) = Unit
    }
}

/**
 * 내비를 보고 있어도 보이게 시스템 알림(헤드업)으로 띄움. 중요도 높음 채널 + 버튼 최대 3개.
 * 주행 중 화면 규칙: 제목 한 줄 + 짧은 글 + 큰 버튼. 색은 앱 강조색.
 */
class SystemAlerts(context: Context) : AlertSink {
    private val app = context.applicationContext
    private val nm = app.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "아기 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "수유 · 기저귀 휴게소 제안, 아기 잠듦 · 깸 알림"
                setSound(null, null) // 소리는 앱 음성 안내가 맡음 (자는 아기를 깨우지 않게 작게)
                enableVibration(false)
            },
        )
    }

    override fun show(alert: CarAlert) {
        val open = PendingIntent.getActivity(
            app, alert.kind.id, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_seat)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(Notification.BigTextStyle().bigText(alert.text))
            .setColor(ACCENT)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(alert.kind != AlertKind.MUSIC)
            .setOngoing(alert.kind == AlertKind.MUSIC) // 노래는 끌 때까지 남김
        if (alert.kind != AlertKind.MUSIC) b.setTimeoutAfter(TIMEOUT_MS)
        alert.buttons.take(3).forEachIndexed { i, btn ->
            val pi = PendingIntent.getBroadcast(
                app, alert.kind.id * 10 + i,
                Intent(app, AlertActionReceiver::class.java).putExtra(EXTRA_ACTION, btn.id).putExtra(EXTRA_KIND, alert.kind.name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(null, btn.label, pi).build())
        }
        runCatching { nm.notify(alert.kind.id, b.build()) }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    override fun cancel(kind: AlertKind) {
        runCatching { nm.cancel(kind.id) }
    }

    companion object {
        private const val TAG = "CarAlerts"
        private const val CHANNEL = "baby_alert"
        private const val ACCENT = 0xFF3EC5F0.toInt()
        private const val TIMEOUT_MS = 3 * 60_000L
        const val EXTRA_ACTION = "action"
        const val EXTRA_KIND = "kind"
    }
}

/** 알림 버튼을 누르면 (앱 안에서만 받음, exported=false) */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(SystemAlerts.EXTRA_ACTION) ?: return
        val kind = runCatching { AlertKind.valueOf(intent.getStringExtra(SystemAlerts.EXTRA_KIND) ?: "") }.getOrNull()
        kind?.let { context.getSystemService(NotificationManager::class.java).cancel(it.id) }
        (context.applicationContext as ModuuiApp).graph.controller.onAlertAction(id)
    }
}
