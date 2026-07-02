package conectate.com.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import conectate.com.MainActivity
import conectate.com.R
import conectate.com.data.repository.VoiceRoomRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class VoiceForegroundService : Service() {
    companion object {
        const val ACTION_START = "conectate.com.action.START_VOICE_FOREGROUND"
        const val ACTION_UPDATE = "conectate.com.action.UPDATE_VOICE_FOREGROUND"
        const val ACTION_STOP = "conectate.com.action.STOP_VOICE_FOREGROUND"
        const val ACTION_HANGUP = "conectate.com.action.HANGUP_VOICE_FOREGROUND"
        const val ACTION_TOGGLE_MUTE = "conectate.com.action.TOGGLE_MUTE_VOICE_FOREGROUND"

        const val EXTRA_ROOM_ID = "extra_room_id"
        const val EXTRA_ROOM_NAME = "extra_room_name"
        const val EXTRA_READABLE_ID = "extra_readable_id"
        const val EXTRA_PARTICIPANT_COUNT = "extra_participant_count"
        const val EXTRA_IS_MUTED = "extra_is_muted"
        const val EXTRA_VIDEO_MODE = "extra_video_mode"

        private const val CHANNEL_ID = "active_voice_room"
        private const val NOTIFICATION_ID = 4_031
    }

    @Inject lateinit var voiceRoomRepository: VoiceRoomRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentRoomId = ""
    private var currentRoomName = "Sala de voz"
    private var currentParticipantCount = 0
    private var currentIsMuted = false
    private var currentVideoMode = "none"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANGUP -> {
                val roomId = intent.getStringExtra(EXTRA_ROOM_ID)?.trim().orEmpty()
                    .ifBlank { currentRoomId }
                serviceScope.launch {
                    voiceRoomRepository.leaveRoom(roomId.ifBlank { null })
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_MUTE -> {
                currentIsMuted = !currentIsMuted
                voiceRoomRepository.toggleMute(currentIsMuted)
            }

            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        intent?.getStringExtra(EXTRA_ROOM_ID)?.trim()?.takeIf { it.isNotBlank() }?.let {
            currentRoomId = it
        }
        intent?.getStringExtra(EXTRA_ROOM_NAME)?.trim()?.takeIf { it.isNotBlank() }?.let {
            currentRoomName = it
        }
        if (intent?.hasExtra(EXTRA_PARTICIPANT_COUNT) == true) {
            currentParticipantCount = intent.getIntExtra(EXTRA_PARTICIPANT_COUNT, 0).coerceAtLeast(0)
        }
        if (intent?.hasExtra(EXTRA_IS_MUTED) == true) {
            currentIsMuted = intent.getBooleanExtra(EXTRA_IS_MUTED, false)
        }
        intent?.getStringExtra(EXTRA_VIDEO_MODE)?.trim()?.lowercase()?.let {
            currentVideoMode = it
        }

        val requestedType = foregroundServiceType()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                requestedType
            )
        } catch (error: Exception) {
            android.util.Log.e("VoiceForegroundService", "Could not start foreground service", error)
            // En Android 14+, si no tenemos permisos para micrófono/cámara, Android lanza SecurityException.
            // Al capturarlo, evitamos el crash, pero si nos llamaron con startForegroundService,
            // Android cerrará la app en 10s si no llamamos a startForeground exitosamente.
            // Para evitarlo, intentamos iniciar sin un tipo (lo cual podría fallar o no según la API),
            // o simplemente detenemos el servicio limpiamente.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            } catch (ignored: Exception) {}
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundFallbackOrStop(requestedType: Int) {
        // Obsoleto, integrado arriba.
    }

    private fun foregroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return 0
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return when (currentVideoMode) {
                "screen" -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else -> 0
            }
        }

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return when (currentVideoMode) {
            "camera" -> type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            "screen" -> type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else -> type
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            currentRoomId.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("room_id", currentRoomId)
                putExtra("type", "voice_room")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muteIntent = PendingIntent.getService(
            this,
            4_032,
            Intent(this, VoiceForegroundService::class.java).apply {
                action = ACTION_TOGGLE_MUTE
                putExtra(EXTRA_ROOM_ID, currentRoomId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hangupIntent = PendingIntent.getService(
            this,
            4_033,
            Intent(this, VoiceForegroundService::class.java).apply {
                action = ACTION_HANGUP
                putExtra(EXTRA_ROOM_ID, currentRoomId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(currentRoomName)
            .setContentText("Sala de voz activa · $currentParticipantCount conectados")
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                R.drawable.ic_notification,
                if (currentIsMuted) "Activar micrófono" else "Silenciar",
                muteIntent
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Salir", hangupIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Sala de voz activa",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene el micrófono y la cámara activos mientras estás en una sala."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
