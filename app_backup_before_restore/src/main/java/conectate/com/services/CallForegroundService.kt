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

class CallForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_UPDATE,
            ACTION_START,
            null -> {
                val title = intent?.getStringExtra(EXTRA_CALL_TITLE).orEmpty().ifBlank { "Llamada" }
                val muted = intent?.getBooleanExtra(EXTRA_IS_MUTED, false) == true
                startForeground(NOTIFICATION_ID, buildNotification(title, muted))
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(title: String, muted: Boolean): Notification {
        ensureChannel()
        val subtitle = if (muted) "Microfono silenciado" else "Llamada en curso"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(subtitle)
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
            "Llamadas activas",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene la llamada activa en segundo plano"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "START"
        const val ACTION_UPDATE = "UPDATE"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_CALL_TITLE = "extra_call_title"
        const val EXTRA_IS_MUTED = "extra_is_muted"

        private const val CHANNEL_ID = "active_calls"
        private const val NOTIFICATION_ID = 4102
    }
}
