package conectate.com.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import conectate.com.MainActivity
import conectate.com.core.concurrency.newSafeScope
import conectate.com.domain.calls.CallForegroundSession
import conectate.com.domain.calls.CallForegroundSessionController
import conectate.com.util.TrustedNavigationIntents
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class CallForegroundService : Service() {

    @Inject
    lateinit var callRepository: CallForegroundSessionController

    private val serviceScope = newSafeScope("CallForegroundService", Dispatchers.Main)
    private lateinit var mediaWakeLocks: ActiveMediaWakeLocks
    private var currentSessionId = ""
    private var currentCallId = ""

    companion object {
        const val CHANNEL_ID = "CallServiceChannel"
        const val ACTION_HANGUP = "ACTION_HANGUP_CALL"
        const val ACTION_MUTE = "ACTION_MUTE_CALL"
        const val ACTION_STOP = "ACTION_STOP_CALL"
        const val ACTION_START = "START"
        const val ACTION_UPDATE = "UPDATE"

        const val EXTRA_CALL_ID = "EXTRA_CALL_ID"
        const val EXTRA_SESSION_ID = "EXTRA_CALL_SESSION_ID"

        const val EXTRA_CALL_TITLE = "EXTRA_CALL_TITLE"
        const val EXTRA_IS_MUTED = "EXTRA_IS_MUTED"
        const val EXTRA_IS_VIDEO = "EXTRA_IS_VIDEO"
    }

    override fun onCreate() {
        super.onCreate()
        mediaWakeLocks = ActiveMediaWakeLocks(this, "Call")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (intent?.action == ACTION_STOP) {
            stopOwnedForeground(callId, sessionId, startId)
            return START_NOT_STICKY
        }

        val session = callRepository.currentForegroundSession()
        if (!matches(session, callId, sessionId)) {
            if (currentSessionId.isBlank()) stopSelf(startId)
            return START_NOT_STICKY
        }
        val owner = requireNotNull(session)
        when (intent?.action) {
            ACTION_START, ACTION_UPDATE -> promote(owner, startId)
            ACTION_HANGUP -> {
                if (!owns(callId, sessionId)) {
                    if (currentSessionId.isBlank()) stopSelf(startId)
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    val closed = runCatching { callRepository.hangup(sessionId) }
                        .getOrElse { Result.failure(it) }
                    if (!isActive) return@launch
                    if (closed.isSuccess) stopOwnedForeground(callId, sessionId, startId, requireEnded = true)
                }
            }
            ACTION_MUTE -> {
                if (!owns(callId, sessionId) || !intent.hasExtra(EXTRA_IS_MUTED)) {
                    if (currentSessionId.isBlank()) stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (callRepository.setMuted(sessionId, !intent.getBooleanExtra(EXTRA_IS_MUTED, false))) {
                    callRepository.currentForegroundSession()?.takeIf { matches(it, callId, sessionId) }
                        ?.let { promote(it, startId) }
                }
            }
            else -> if (currentSessionId.isBlank()) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun matches(session: CallForegroundSession?, callId: String, sessionId: String): Boolean =
        session != null && sessionId.isNotBlank() && callId.isNotBlank() && session.sessionId == sessionId &&
            session.callId.equals(callId, ignoreCase = true)

    private fun owns(callId: String, sessionId: String): Boolean =
        sessionId.isNotBlank() && currentSessionId == sessionId && currentCallId.equals(callId, ignoreCase = true)

    private fun promote(session: CallForegroundSession, startId: Int) {
        try {
            ServiceCompat.startForeground(this, 2, buildNotification(session), foregroundServiceType(session.isVideo))
            currentSessionId = session.sessionId
            currentCallId = session.callId
            mediaWakeLocks.acquire()
        } catch (error: Exception) {
            android.util.Log.e("CallForegroundService", "Could not start foreground service", error)
            if (currentSessionId.isBlank()) stopSelf(startId)
        }
    }

    private fun stopOwnedForeground(callId: String, sessionId: String, startId: Int, requireEnded: Boolean = false) {
        if (!owns(callId, sessionId)) {
            if (currentSessionId.isBlank()) stopSelf(startId)
            return
        }
        val active = callRepository.currentForegroundSession()
        if (active != null && (!matches(active, callId, sessionId) || requireEnded)) return
        currentSessionId = ""
        currentCallId = ""
        mediaWakeLocks.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun foregroundServiceType(isVideo: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return 0
        }

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (isVideo) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        return type
    }

    private fun buildNotification(session: CallForegroundSession): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            data = sessionUri(session, "return")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("type", "call_return")
            putExtra("call_id", session.callId)
        }.also { TrustedNavigationIntents.markTrusted(this, it) }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangupIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_HANGUP
            data = sessionUri(session, "hangup")
            putExtra(EXTRA_CALL_ID, session.callId)
            putExtra(EXTRA_SESSION_ID, session.sessionId)
        }
        val hangupPendingIntent = PendingIntent.getService(
            this,
            0,
            hangupIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, CallForegroundService::class.java).apply {
            action = ACTION_MUTE
            data = sessionUri(session, if (session.isMuted) "unmute" else "mute")
            putExtra(EXTRA_CALL_ID, session.callId)
            putExtra(EXTRA_SESSION_ID, session.sessionId)
            putExtra(EXTRA_IS_MUTED, session.isMuted)
        }
        val mutePendingIntent = PendingIntent.getService(
            this,
            1,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteActionTitle = if (session.isMuted) "Activar Mic" else "Silenciar"
        val muteIcon = if (session.isMuted) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_lock_silent_mode

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Llamada")
            .setContentText(session.title)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .addAction(muteIcon, muteActionTitle, mutePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Colgar", hangupPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun sessionUri(session: CallForegroundSession, action: String): Uri = Uri.Builder()
        .scheme("novvin-internal").authority("call-session")
        .appendPath(session.sessionId).appendPath(session.callId).appendPath(action).build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
        currentSessionId = ""
        currentCallId = ""
        if (::mediaWakeLocks.isInitialized) mediaWakeLocks.release()
        serviceScope.cancel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (ignored: Exception) {}
        super.onDestroy()
    }
}
