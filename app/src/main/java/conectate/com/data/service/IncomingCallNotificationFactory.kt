package conectate.com.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import conectate.com.R
import conectate.com.core.media.NotificationAvatarDecoder
import conectate.com.data.model.CallInvite
import conectate.com.presentation.calls.IncomingCallActivity
import conectate.com.util.NotificationAccountTarget
import conectate.com.util.inferMimeTypeFromBytes
import conectate.com.util.putNotificationAccountTarget
import java.io.ByteArrayOutputStream

object IncomingCallNotificationFactory {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            context.getString(R.string.call_notification_channel_id),
            "Llamadas",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Llamadas entrantes"
            enableVibration(true)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // IncomingCallForegroundService owns the single looping ringtone.
            setSound(null, null)
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        invite: CallInvite,
        avatarBitmap: Bitmap?,
        avatarBytes: ByteArray?,
        accountTarget: NotificationAccountTarget? = null,
    ): Notification {
        val notificationId = invite.callId.hashCode()

        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            buildIncomingCallActivityIntent(context, invite, avatarBytes, accountTarget),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Android 12+ blocks notification trampolines (notification -> receiver
        // -> activity). Open the lightweight incoming-call activity directly so
        // answering a cold-start call is immediate and reliable.
        val acceptPending = PendingIntent.getActivity(
            context,
            notificationId + 1,
            buildIncomingCallActivityIntent(context, invite, avatarBytes, accountTarget, autoAccept = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declinePending = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            Intent(context, IncomingCallActionReceiver::class.java).apply {
                action = IncomingCallActionReceiver.ACTION_DECLINE
                data = callActionUri(invite.callId, "decline")
                copyInviteExtras(invite)
                putNotificationAccountTarget(accountTarget)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callerPerson = Person.Builder()
            .setName(invite.callerName.ifBlank { "Novvin" })
            .setImportant(true)
            .apply {
                avatarBitmap?.let { setIcon(IconCompat.createWithBitmap(it)) }
            }
            .build()

        return NotificationCompat.Builder(context, context.getString(R.string.call_notification_channel_id))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(invite.callerName.ifBlank { "Novvin" })
            .setContentText(if (invite.isVideo) "Videollamada entrante" else "Llamada entrante")
            .setSubText(accountTarget?.presentableLabel()?.let { "Novvin · $it" } ?: "Novvin")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setLargeIcon(avatarBitmap)
            .setColor(ContextCompat.getColor(context, R.color.red_primary))
            .setColorized(true)
            .setContentIntent(pending)
            .setDeleteIntent(declinePending)
            .setFullScreenIntent(pending, true)
            .setTimeoutAfter(45_000L)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(callerPerson, declinePending, acceptPending))
            .build()
    }

    internal fun buildIncomingCallActivityIntent(
        context: Context,
        invite: CallInvite,
        avatarBytes: ByteArray? = null,
        accountTarget: NotificationAccountTarget? = null,
        autoAccept: Boolean = false,
    ): Intent {
        return Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Android uses the URI for PendingIntent identity. Extras can be updated independently.
            data = callActionUri(invite.callId, if (autoAccept) "answer" else "open")
            copyInviteExtras(invite)
            avatarBytes?.takeIf { it.size in 1..INCOMING_CALL_AVATAR_MAX_BYTES }?.let {
                putExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES, it)
            }
            if (autoAccept) putExtra(IncomingCallActivity.EXTRA_AUTO_ACCEPT, true)
            putNotificationAccountTarget(accountTarget)
        }
    }

    private fun callActionUri(callId: String, action: String): Uri = Uri.Builder()
        .scheme("novvin")
        .authority("call-notification")
        .appendPath(callId)
        .appendPath(action)
        .build()
}

private fun Intent.copyInviteExtras(invite: CallInvite) {
    putExtra("call_id", invite.callId)
    putExtra("caller_user_id", invite.callerUserId)
    putExtra("caller_name", invite.callerName)
    putExtra("caller_avatar_url", invite.callerAvatarUrl ?: "")
    putExtra("call_title", invite.title)
    putExtra("call_context_type", invite.contextType)
    putExtra("call_context_id", invite.contextId ?: "")
    putExtra("call_is_video", invite.isVideo)
}

internal const val INCOMING_CALL_AVATAR_MAX_BYTES = 96_000

internal fun Bitmap.encodeIncomingCallAvatar(): ByteArray? {
    if (isRecycled) return null
    val output = ByteArrayOutputStream()
    for (quality in 90 downTo 45 step 15) {
        output.reset()
        if (compress(Bitmap.CompressFormat.JPEG, quality, output) &&
            output.size() in 1..INCOMING_CALL_AVATAR_MAX_BYTES
        ) {
            return output.toByteArray()
        }
    }
    return null
}

internal fun decodeIncomingCallAvatar(context: Context, bytes: ByteArray): Bitmap? {
    if (bytes.size !in 4..INCOMING_CALL_AVATAR_MAX_BYTES || inferMimeTypeFromBytes(bytes) != "image/jpeg") {
        return null
    }
    return try {
        NotificationAvatarDecoder.decode(context, bytes)
    } catch (_: Exception) {
        null
    }
}
