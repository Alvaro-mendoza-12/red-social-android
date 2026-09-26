package conectate.com.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import conectate.com.MainActivity
import conectate.com.R
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.repository.LiveRepository
import conectate.com.util.TrustedNavigationIntents
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class LiveForegroundState(
    val liveId: String = "",
    val types: Int = 0
)

// These foreground-service type constants are compile-time bit flags. The
// actual typed startForeground call remains guarded by the corresponding SDK
// checks below, so referencing the flags is safe on the supported API range.
@SuppressLint("InlinedApi")
internal object LiveForegroundPolicy {
    private val supportedTypes =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

    fun sanitizeTypes(
        requestedTypes: Int,
        role: String,
        cameraPermissionGranted: Boolean,
        microphonePermissionGranted: Boolean
    ): Int {
        if (!role.trim().equals("host", ignoreCase = true)) return 0

        var result = requestedTypes and supportedTypes
        if (!cameraPermissionGranted) {
            result = result and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA.inv()
        }
        if (!microphonePermissionGranted) {
            result = result and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE.inv()
        }
        return result
    }
}

@AndroidEntryPoint
class LiveForegroundService : Service() {

    @Inject
    lateinit var liveRepository: LiveRepository

    private val serviceScope = newSafeScope("LiveForegroundService", Dispatchers.Main)
    private var currentLiveId: String = ""

    companion object {
        const val CHANNEL_ID = "LiveServiceChannel"
        const val ACTION_START = "ACTION_START_LIVE"
        const val ACTION_END = "ACTION_END_LIVE"
        const val ACTION_STOP = "ACTION_STOP_LIVE"

        const val EXTRA_LIVE_ID = "EXTRA_LIVE_ID"
        const val EXTRA_LIVE_TITLE = "EXTRA_LIVE_TITLE"
        const val EXTRA_LIVE_ROLE = "EXTRA_LIVE_ROLE"
        const val EXTRA_TYPE = "EXTRA_TYPE"

        private const val NOTIFICATION_ID = 3
        private const val END_REQUEST_CODE = 31
        private val _foregroundState = MutableStateFlow(LiveForegroundState())
        internal val foregroundState: StateFlow<LiveForegroundState> = _foregroundState.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_END -> {
                serviceScope.launch {
                    runCatching { liveRepository.endLive() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        val liveId = intent?.getStringExtra(EXTRA_LIVE_ID).orEmpty()
        val title = intent?.getStringExtra(EXTRA_LIVE_TITLE).orEmpty().ifBlank { "Live" }
        val role = intent?.getStringExtra(EXTRA_LIVE_ROLE).orEmpty()
        val requestedTypes = intent?.getIntExtra(EXTRA_TYPE, 0) ?: 0

        if (liveId.isBlank() || !role.equals("host", ignoreCase = true)) {
            android.util.Log.w("LiveForegroundService", "Rejecting invalid live foreground request")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val types = LiveForegroundPolicy.sanitizeTypes(
            requestedTypes = requestedTypes,
            role = role,
            cameraPermissionGranted = hasPermission(Manifest.permission.CAMERA),
            microphonePermissionGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && types == 0) {
            android.util.Log.w("LiveForegroundService", "No eligible foreground type for host live $liveId")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val notification = buildNotification(liveId, title, role)
        val promoted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && types != 0) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (error: Exception) {
            android.util.Log.e(
                "LiveForegroundService",
                "Unable to promote live $liveId with type=$types",
                error
            )
            false
        }

        if (!promoted) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        currentLiveId = liveId
        _foregroundState.value = LiveForegroundState(liveId = liveId, types = types)

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
            liveId.hashCode(),
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = Intent(this, LiveForegroundService::class.java).apply {
            action = ACTION_END
        }
        val endPendingIntent = PendingIntent.getService(
            this,
            END_REQUEST_CODE,
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

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        if (_foregroundState.value.liveId == currentLiveId) {
            _foregroundState.value = LiveForegroundState()
        }
        currentLiveId = ""
        super.onDestroy()
        serviceScope.cancel()
    }

}
