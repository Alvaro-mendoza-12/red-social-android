package conectate.com.services

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.model.CallInvite
import conectate.com.data.service.IncomingCallNotificationFactory
import conectate.com.presentation.calls.IncomingCallActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class IncomingCallForegroundService : Service() {

    private val serviceScope = newSafeScope("IncomingCallForegroundService", Dispatchers.Main)
    private var timeoutJob: Job? = null
    private var currentNotificationId: Int = 0
    private var ringtone: Ringtone? = null

    override fun onCreate() {
        super.onCreate()
        IncomingCallNotificationFactory.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopIncomingCallUi()
                return START_NOT_STICKY
            }
        }

        val invite = intent?.toInviteOrNull()
        if (invite == null) {
            stopIncomingCallUi()
            return START_NOT_STICKY
        }

        currentNotificationId = invite.callId.hashCode()
        val avatarBitmap = intent.toAvatarBitmapOrNull()
        try {
            startForeground(currentNotificationId, buildNotification(invite, avatarBitmap))
        } catch (e: Exception) {
            android.util.Log.e("IncomingCallService", "Failed to start foreground service", e)
            // Do not ring invisibly when Android could not publish the call controls.
            stopIncomingCallUi()
            return START_NOT_STICKY
        }
        startLoopingRingtone()

        timeoutJob?.cancel()
        timeoutJob = serviceScope.launch {
            delay(45_000L)
            stopIncomingCallUi()
        }

        return START_NOT_STICKY
    }

    private fun stopIncomingCallUi() {
        timeoutJob?.cancel()
        timeoutJob = null
        stopLoopingRingtone()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (currentNotificationId != 0) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(currentNotificationId)
            currentNotificationId = 0
        }
        stopSelf()
    }

    private fun buildNotification(invite: CallInvite, avatarBitmap: Bitmap?): Notification {
        return IncomingCallNotificationFactory.build(
            context = this,
            invite = invite,
            avatarBitmap = avatarBitmap,
            avatarBytes = avatarBitmap?.toNotificationAvatarBytes()
        )
    }

    private fun startLoopingRingtone() {
        runCatching {
            if (ringtone == null) {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        isLooping = true
                    }
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
            }
            ringtone?.play()
        }.onFailure {
            android.util.Log.w("IncomingCallService", "Failed to start looping ringtone", it)
        }
    }

    private fun stopLoopingRingtone() {
        runCatching { ringtone?.stop() }
            .onFailure {
                android.util.Log.w("IncomingCallService", "Failed to stop looping ringtone", it)
            }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopLoopingRingtone()
        serviceScope.cancel()
    }

    companion object {
        private const val ACTION_SHOW = "conectate.com.action.SHOW_INCOMING_CALL"
        private const val ACTION_STOP = "conectate.com.action.STOP_INCOMING_CALL"

        fun start(context: Context, invite: CallInvite, avatarBytes: ByteArray?) {
            val intent = Intent(context, IncomingCallForegroundService::class.java).apply {
                action = ACTION_SHOW
                putExtra("call_id", invite.callId)
                putExtra("caller_user_id", invite.callerUserId)
                putExtra("caller_name", invite.callerName)
                putExtra("caller_avatar_url", invite.callerAvatarUrl ?: "")
                putExtra("call_title", invite.title)
                putExtra("call_context_type", invite.contextType)
                putExtra("call_context_id", invite.contextId ?: "")
                putExtra("call_is_video", invite.isVideo)
                avatarBytes?.let {
                    putExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES, it)
                }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IncomingCallForegroundService::class.java))
        }
    }
}

private fun Intent.toInviteOrNull(): CallInvite? {
    val callId = getStringExtra("call_id")?.trim().orEmpty()
    val callerUserId = getStringExtra("caller_user_id")?.trim().orEmpty()
    val callerName = getStringExtra("caller_name")?.trim().orEmpty()
    val callerAvatarUrl = getStringExtra("caller_avatar_url")?.trim().orEmpty().ifBlank { null }
    val title = getStringExtra("call_title")?.trim().orEmpty().ifBlank { callerName }
    val contextType = getStringExtra("call_context_type")?.trim().orEmpty().ifBlank { "dm" }
    val contextId = getStringExtra("call_context_id")?.trim().orEmpty().ifBlank { null }
    val isVideo = getBooleanExtra("call_is_video", false)

    if (callId.isBlank() || callerUserId.isBlank()) return null
    return CallInvite(
        callId = callId,
        title = title,
        callerUserId = callerUserId,
        callerName = callerName.ifBlank { "Usuario" },
        callerAvatarUrl = callerAvatarUrl,
        contextType = contextType,
        contextId = contextId,
        isVideo = isVideo
    )
}

private fun Intent.toAvatarBitmapOrNull(): Bitmap? {
    val bytes = getByteArrayExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES) ?: return null
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
}

private fun Bitmap.toNotificationAvatarBytes(maxSizeBytes: Int = 96_000): ByteArray? {
    var quality = 90
    while (quality >= 45) {
        val stream = java.io.ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        if (bytes.size <= maxSizeBytes || quality == 45) {
            return bytes
        }
        quality -= 15
    }
    return null
}
