package conectate.com.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import conectate.com.MainActivity
import conectate.com.R
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.repository.LiveRepository
import conectate.com.util.TrustedNavigationIntents
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LiveForegroundService : Service() {

    @Inject
    lateinit var liveRepository: LiveRepository

    private val serviceScope = newSafeScope("LiveForegroundService", Dispatchers.Main)

    companion object {
        const val CHANNEL_ID = "LiveServiceChannel"
        const val ACTION_END = "ACTION_END_LIVE"
        const val ACTION_STOP = "ACTION_STOP_LIVE"

        const val EXTRA_LIVE_ID = "EXTRA_LIVE_ID"
        const val EXTRA_LIVE_TITLE = "EXTRA_LIVE_TITLE"
        const val EXTRA_LIVE_ROLE = "EXTRA_LIVE_ROLE"
        const val EXTRA_TYPE = "EXTRA_TYPE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val liveId = intent?.getStringExtra(EXTRA_LIVE_ID).orEmpty()
        val title = intent?.getStringExtra(EXTRA_LIVE_TITLE).orEmpty().ifBlank { "Live" }
        val role = intent?.getStringExtra(EXTRA_LIVE_ROLE).orEmpty().ifBlank { "viewer" }
        val type = intent?.getIntExtra(EXTRA_TYPE, 0) ?: 0

        val notification = buildNotification(liveId, title, role)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            runCatching {
                startForeground(3, notification, type)
            }.onFailure { error ->
                android.util.Log.w("LiveForegroundService", "startForeground with type=$type failed. Falling back.", error)
                startForeground(3, notification)
            }
        } else {
            startForeground(3, notification)
        }

        when (intent?.action) {
            ACTION_END -> {
                serviceScope.launch {
                    runCatching { liveRepository.endLive() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> Unit // START/UPDATE
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(liveId: String, liveTitle: String, liveRole: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("type", "live_return")
            putExtra("live_id", liveId)
            putExtra("live_role", liveRole)
        }.also { TrustedNavigationIntents.markTrusted(this, it) }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = Intent(this, LiveForegroundService::class.java).apply {
            action = ACTION_END
        }
        val endPendingIntent = PendingIntent.getService(
            this,
            0,
            endIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (liveRole.equals("host", ignoreCase = true)) "Transmitiendo en vivo" else "Live en curso"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(liveTitle)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar", endPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Lives",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
