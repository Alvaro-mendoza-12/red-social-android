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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

@AndroidEntryPoint
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class VoiceForegroundService : Service() {

    @Inject
    lateinit var voiceRoomRepository: VoiceRoomRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    @Volatile
    private var explicitShutdown = false

    companion object {
        const val CHANNEL_ID = "VoiceServiceChannel"
        const val ACTION_HANGUP = "ACTION_HANGUP"
        const val ACTION_MUTE = "ACTION_MUTE"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_ROOM_NAME = "EXTRA_ROOM_NAME"
        const val EXTRA_IS_MUTED = "EXTRA_IS_MUTED"
        const val EXTRA_TYPE = "EXTRA_TYPE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val roomName = intent?.getStringExtra(EXTRA_ROOM_NAME) ?: "Sala de Voz"
        val isMuted = intent?.getBooleanExtra(EXTRA_IS_MUTED, false) ?: false
        val type = resolveForegroundType(intent)

        // Always call startForeground for ANY action to satisfy Android requirements
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type != 0) {
            startForeground(1, buildNotification(roomName, isMuted), type)
        } else {
            startForeground(1, buildNotification(roomName, isMuted))
        }

        when (intent?.action) {
            "START", "UPDATE_TYPE" -> {
                android.util.Log.d("VoiceService", "Service started/updated with type: $type")
            }
            ACTION_HANGUP -> {
                explicitShutdown = true
                serviceScope.launch {
                    try {
                        voiceRoomRepository.leaveRoom()
                    } catch (e: Exception) {
                        android.util.Log.e("VoiceService", "Error in hangup action", e)
                    }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                explicitShutdown = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_MUTE -> {
                // Mute logic is handled by toggleMute
                voiceRoomRepository.toggleMute(!isMuted)
                updateNotification(roomName, !isMuted)
            }
        }

        return START_NOT_STICKY
    }

    private fun resolveForegroundType(intent: Intent?): Int {
        val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return intent?.getIntExtra(EXTRA_TYPE, fallback) ?: fallback
    }

    private fun buildNotification(roomName: String, isMuted: Boolean): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("type", "voice_return")
            voiceRoomRepository.getCurrentRoomId()?.let { roomId ->
                putExtra("room_id", roomId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangupIntent = Intent(this, VoiceForegroundService::class.java).apply {
            action = ACTION_HANGUP
        }
        val hangupPendingIntent = PendingIntent.getService(
            this, 0, hangupIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, VoiceForegroundService::class.java).apply {
            action = ACTION_MUTE
            putExtra(EXTRA_ROOM_NAME, roomName)
            putExtra(EXTRA_IS_MUTED, isMuted)
        }
        val mutePendingIntent = PendingIntent.getService(
            this, 1, muteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteActionTitle = if (isMuted) "Activar Mic" else "Silenciar"
        val muteIcon = if (isMuted) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_lock_silent_mode

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sala de Voz")
            .setContentText(roomName)
            .setSmallIcon(android.R.drawable.ic_menu_call) 
            .setContentIntent(pendingIntent)
            .addAction(muteIcon, muteActionTitle, mutePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Colgar", hangupPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(roomName: String, isMuted: Boolean) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, buildNotification(roomName, isMuted))
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Llamadas de Voz",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        android.util.Log.d("VoiceService", "Task removed: keeping voice session alive in foreground")
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("VoiceService", "Service destroying (explicitShutdown=$explicitShutdown)")
        serviceScope.cancel()
    }
}
