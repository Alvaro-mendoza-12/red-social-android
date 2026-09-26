package conectate.com.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import conectate.com.R
import conectate.com.data.model.CallInvite
import conectate.com.presentation.calls.IncomingCallActivity

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
        avatarBytes: ByteArray?
    ): Notification {
        val notificationId = invite.callId.hashCode()

        var pending = PendingIntent.getActivity(
            context,
            notificationId,
            buildIncomingCallActivityIntent(context, invite, avatarBytes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Android 12+ blocks notification trampolines (notification -> receiver
        // -> activity). Open the lightweight incoming-call activity directly so
        // answering a cold-start call is immediate and reliable.
        val acceptPending = PendingIntent.getActivity(
            context,
            notificationId + 1,
            buildIncomingCallActivityIntent(context, invite, avatarBytes).apply {
                putExtra(IncomingCallActivity.EXTRA_AUTO_ACCEPT, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declinePending = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            Intent(context, IncomingCallActionReceiver::class.java).apply {
                action = IncomingCallActionReceiver.ACTION_DECLINE
                copyInviteExtras(invite, avatarBytes)
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
            .setSubText("Novvin")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setSilent(true)
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

    private fun buildIncomingCallActivityIntent(
        context: Context,
        invite: CallInvite,
        avatarBytes: ByteArray?
    ): Intent {
        return Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            copyInviteExtras(invite, avatarBytes)
        }
    }
}

private fun Intent.copyInviteExtras(invite: CallInvite, avatarBytes: ByteArray?): Intent {
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
    return this
}



