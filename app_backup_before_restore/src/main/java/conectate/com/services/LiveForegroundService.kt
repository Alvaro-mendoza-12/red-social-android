package conectate.com.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import conectate.com.R

class LiveForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_LIVE_TITLE).orEmpty().ifBlank { "Live" }
        val role = intent?.getStringExtra(EXTRA_LIVE_ROLE).orEmpty().ifBlank { "host" }
        startForeground(NOTIFICATION_ID, buildNotification(title, role))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(title: String, role: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("Live activo (${role})")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lives activos",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene transmisiones en vivo en segundo plano"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_LIVE_ID = "extra_live_id"
        const val EXTRA_LIVE_TITLE = "extra_live_title"
        const val EXTRA_LIVE_ROLE = "extra_live_role"
        const val EXTRA_TYPE = "extra_type"

        private const val CHANNEL_ID = "active_live"
        private const val NOTIFICATION_ID = 4103
    }
}
