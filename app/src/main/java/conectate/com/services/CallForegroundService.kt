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
import conectate.com.data.repository.CallRepository
import conectate.com.util.TrustedNavigationIntents
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CallForegroundService : Service() {

    @Inject
    lateinit var callRepository: CallRepository

    private val serviceScope = newSafeScope("CallForegroundService", Dispatchers.Main)

    companion object {
        const val CHANNEL_ID = "CallServiceChannel"
        const val ACTION_HANGUP = "ACTION_HANGUP_CALL"
        const val ACTION_MUTE = "ACTION_MUTE_CALL"
        const val ACTION_STOP = "ACTION_STOP_CALL"

        const val EXTRA_CALL_TITLE = "EXTRA_CALL_TITLE"
        const val EXTRA_IS_MUTED = "EXTRA_IS_MUTED"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_CALL_TITLE) ?: "Llamada"
        val isMuted = intent?.getBooleanExtra(EXTRA_IS_MUTED, false) ?: false

        startForeground(2, buildNotification(title, isMuted))

        when (intent?.action) {
            ACTION_HANGUP -> {
                serviceScope.launch {
                    runCatching { callRepository.hangup() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_MUTE -> {
                callRepository.toggleMute(!isMuted)
                updateNotification(title, !isMuted)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // START/UPDATE: already running
            }
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(callTitle: String, isMuted: Boolean): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("type", "call_return")
            callRepository.getCurrentCallId()?.let { callId ->
                putExtra("call_id", callId)
            }
        }.also { TrustedNavigationIntents.markTrusted(this, it) }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangupIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_HANGUP
        }
        val hangupPendingIntent = PendingIntent.getService(
            this,
            0,
            hangupIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_MUTE
            putExtra(EXTRA_CALL_TITLE, callTitle)
            putExtra(EXTRA_IS_MUTED, isMuted)
        }
        val mutePendingIntent = PendingIntent.getService(
            this,
            1,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteActionTitle = if (isMuted) "Activar Mic" else "Silenciar"
        val muteIcon = if (isMuted) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_lock_silent_mode

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Llamada")
            .setContentText(callTitle)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .addAction(muteIcon, muteActionTitle, mutePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Colgar", hangupPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(callTitle: String, isMuted: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(2, buildNotification(callTitle, isMuted))
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Llamadas",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
