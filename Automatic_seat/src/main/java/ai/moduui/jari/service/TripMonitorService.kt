package ai.moduui.jari.service

import ai.moduui.jari.R
import ai.moduui.jari.ui.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 주행 중 앱이 화면 뒤로 가도(내비가 앞에 있어도) 수유 시간 확인·아기 상태 조치를 이어 가기 위한 포그라운드 서비스.
 * 여정이 시작될 때만 켜고 도착 후 정차하면 끔 (Pleos 정책: 백그라운드 상주 최소화).
 * 실제 작업은 TripController(앱 프로세스)가 하고, 이 서비스는 프로세스 유지와 알림만 담당.
 */
class TripMonitorService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "여정 지켜보기", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_seat)
            .setContentTitle("모두의 자리")
            .setContentText("아기와 함께하는 여정을 지켜보는 중")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, n)
            }
        } catch (e: Exception) {
            // 백그라운드 시작 제한 등. 서비스 없이도 앱이 화면에 있는 동안은 동작함.
            Log.w("TripMonitorService", "startForeground failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "trip"
        private const val ID = 7

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, TripMonitorService::class.java)) }
                .onFailure { Log.w("TripMonitorService", "start failed", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TripMonitorService::class.java))
        }
    }
}
