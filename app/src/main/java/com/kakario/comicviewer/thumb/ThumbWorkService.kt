package com.kakario.comicviewer.thumb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.kakario.comicviewer.MainActivity
import com.kakario.comicviewer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 썸네일 갱신이 있는 동안만 켜지는 서비스.
 * 화면이 꺼져도 폰이 CPU·와이파이를 재우지 않게 붙잡고, 알림창에 진행 상황을 보여준다.
 * 갱신이 모두 끝나면 스스로 꺼진다.
 */
class ThumbWorkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val loader = ThumbnailLoader.get(applicationContext)
        if (intent?.action == ACTION_STOP) {
            loader.stopAll()
        }
        if (!started) {
            started = true
            ensureChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(loader.status.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            acquireLocks()
            scope.launch {
                val nm = getSystemService(NotificationManager::class.java)
                loader.status.collect { st ->
                    if (st == null) {
                        // 할 일이 끝났으면 알림 내리고 종료
                        ServiceCompat.stopForeground(this@ThumbWorkService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        nm.notify(NOTIFICATION_ID, buildNotification(st))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ComicViewer:thumbs").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L) // 안전장치: 최대 6시간
        }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm?.createWifiLock(mode, "ComicViewer:thumbs")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "썸네일 갱신", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "표지 썸네일을 만드는 동안 진행 상황을 보여줘요"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(st: ThumbnailLoader.Status?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ThumbWorkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cur = st?.current
        val title = cur?.let { "'${it.label}' ${st.phase}" } ?: "썸네일 갱신 준비 중"
        val text = buildString {
            if (cur != null) append("${cur.done}/${cur.total ?: "?"}")
            if (st != null && st.waiting.isNotEmpty()) append(" · 대기 ${st.waiting.size}개")
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_thumbs)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "모두 중지", stop)
        val total = cur?.total
        if (cur != null && total != null && total > 0) b.setProgress(total, cur.done, false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val CHANNEL_ID = "thumb_work"
        private const val NOTIFICATION_ID = 7001
        private const val ACTION_STOP = "com.kakario.comicviewer.STOP_THUMBS"

        /** 앱이 화면에 떠 있을 때 갱신이 시작되면 호출 — 이미 켜져 있으면 아무 일 없음. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ThumbWorkService::class.java))
            } catch (_: Exception) {
                // 백그라운드에서 시작이 막힌 경우 등 — 화면이 켜져 있는 동안엔 그대로 진행됨
            }
        }
    }
}
