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
import conectate.com.data.repository.VoiceRoomRepository
import conectate.com.util.TrustedNavigationIntents
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import conectate.com.core.concurrency.newSafeScope

@AndroidEntryPoint
class VoiceForegroundService : Service() {
    @Inject
    lateinit var voiceRoomRepository: VoiceRoomRepository

    private val serviceScope = newSafeScope("VoiceForegroundService", Dispatchers.Main)

    companion object {
        const val CHANNEL_ID = "VoiceRoomServiceChannel"
        const val NOTIFICATION_ID = 4

        const val ACTION_START = "conectate.com.action.START_VOICE_ROOM"
        const val ACTION_UPDATE = "conectate.com.action.UPDATE_VOICE_ROOM"
        const val ACTION_MUTE = "conectate.com.action.MUTE_VOICE_ROOM"
        const val ACTION_STOP = "conectate.com.action.STOP_VOICE_ROOM"

        const val EXTRA_ROOM_ID = "EXTRA_ROOM_ID"
        const val EXTRA_ROOM_NAME = "EXTRA_ROOM_NAME"
        const val EXTRA_READABLE_ID = "EXTRA_READABLE_ID"
        const val EXTRA_PARTICIPANT_COUNT = "EXTRA_PARTICIPANT_COUNT"
        const val EXTRA_IS_MUTED = "EXTRA_IS_MUTED"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val roomId = intent?.getStringExtra(EXTRA_ROOM_ID)
            ?: voiceRoomRepository.getCurrentRoomId().orEmpty()
        val roomName = intent?.getStringExtra(EXTRA_ROOM_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "Sala de voz"
        val readableId = intent?.getStringExtra(EXTRA_READABLE_ID)
        val participantCount = intent?.getIntExtra(EXTRA_PARTICIPANT_COUNT, 0) ?: 0
        val isMuted = intent?.getBooleanExtra(EXTRA_IS_MUTED, false) ?: false

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                roomId = roomId,
                roomName = roomName,
                readableId = readableId,
                participantCount = participantCount,
                isMuted = isMuted
            )
        )

        when (intent?.action) {
            ACTION_MUTE -> {
                val nextMuted = !isMuted
                voiceRoomRepository.toggleMute(nextMuted)
                updateNotification(roomId, roomName, readableId, participantCount, nextMuted)
            }
            ACTION_STOP -> {
                serviceScope.launch {
                    runCatching { voiceRoomRepository.leaveRoom(roomId.takeIf { it.isNotBlank() }) }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_START, ACTION_UPDATE, null -> {
                // The foreground notification is already showing; this path is a safe update.
            }
        }

        return START_STICKY
    }

    private fun buildNotification(
        roomId: String,
        roomName: String,
        readableId: String?,
        participantCount: Int,
        isMuted: Boolean
    ): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("type", "voice_return")
            putExtra("room_id", roomId)
        }.also { TrustedNavigationIntents.markTrusted(this, it) }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, VoiceForegroundService::class.java).apply {
            action = ACTION_MUTE
            putExtra(EXTRA_ROOM_ID, roomId)
            putExtra(EXTRA_ROOM_NAME, roomName)
            putExtra(EXTRA_READABLE_ID, readableId)
            putExtra(EXTRA_PARTICIPANT_COUNT, participantCount)
            putExtra(EXTRA_IS_MUTED, isMuted)
        }
        val mutePendingIntent = PendingIntent.getService(
            this,
            1,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VoiceForegroundService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_ROOM_ID, roomId)
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = buildString {
            if (!readableId.isNullOrBlank()) append("#").append(readableId).append(" · ")
            append(participantCount.coerceAtLeast(1)).append(" en sala")
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(roomName)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(
                if (isMuted) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_lock_silent_mode,
                if (isMuted) "Activar mic" else "Silenciar",
                mutePendingIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Salir", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(
        roomId: String,
        roomName: String,
        readableId: String?,
        participantCount: Int,
        isMuted: Boolean
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(roomId, roomName, readableId, participantCount, isMuted)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Salas de voz",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Sesiones activas de salas de voz"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
