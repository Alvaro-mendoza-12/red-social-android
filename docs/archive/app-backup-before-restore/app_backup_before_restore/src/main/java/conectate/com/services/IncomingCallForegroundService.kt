package conectate.com.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.IBinder
import conectate.com.data.model.CallInvite
import conectate.com.data.service.IncomingCallNotificationFactory

class IncomingCallForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val invite = intent?.toInviteOrNull()
        if (invite != null) {
            IncomingCallNotificationFactory.ensureChannel(this)
            val avatarBytes = intent.getByteArrayExtra(EXTRA_CALLER_AVATAR_BYTES)
            val avatarBitmap = avatarBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            startForeground(
                invite.callId.hashCode(),
                IncomingCallNotificationFactory.build(
                    context = this,
                    invite = invite,
                    avatarBitmap = avatarBitmap,
                    avatarBytes = avatarBytes
                )
            )
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_START = "incoming_call_start"
        const val ACTION_STOP = "incoming_call_stop"
        const val EXTRA_CALLER_AVATAR_BYTES = "extra_caller_avatar_bytes"

        fun start(context: Context, invite: CallInvite, avatarBytes: ByteArray?) {
            val intent = Intent(context, IncomingCallForegroundService::class.java).apply {
                action = ACTION_START
                putInviteExtras(invite)
                avatarBytes?.let { putExtra(EXTRA_CALLER_AVATAR_BYTES, it) }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, IncomingCallForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }
}

private fun Intent.putInviteExtras(invite: CallInvite): Intent {
    putExtra("call_id", invite.callId)
    putExtra("caller_user_id", invite.callerUserId)
    putExtra("caller_name", invite.callerName)
    putExtra("caller_avatar_url", invite.callerAvatarUrl ?: "")
    putExtra("call_title", invite.title)
    putExtra("call_context_type", invite.contextType)
    putExtra("call_context_id", invite.contextId ?: "")
    return this
}

private fun Intent.toInviteOrNull(): CallInvite? {
    val callId = getStringExtra("call_id")?.trim().orEmpty()
    val callerUserId = getStringExtra("caller_user_id")?.trim().orEmpty()
    if (callId.isBlank() || callerUserId.isBlank()) return null
    return CallInvite(
        callId = callId,
        title = getStringExtra("call_title")?.trim().orEmpty().ifBlank { "Llamada" },
        callerUserId = callerUserId,
        callerName = getStringExtra("caller_name")?.trim().orEmpty().ifBlank { "Usuario" },
        callerAvatarUrl = getStringExtra("caller_avatar_url")?.trim().orEmpty().ifBlank { null },
        contextType = getStringExtra("call_context_type")?.trim().orEmpty().ifBlank { "dm" },
        contextId = getStringExtra("call_context_id")?.trim().orEmpty().ifBlank { null }
    )
}
