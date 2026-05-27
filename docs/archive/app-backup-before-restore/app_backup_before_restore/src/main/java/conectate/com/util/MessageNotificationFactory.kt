package conectate.com.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import conectate.com.MainActivity
import conectate.com.R

object MessageNotificationFactory {

    fun build(
        context: Context,
        payload: MessageNotificationPayload
    ): Notification {
        ensureChannel(context, payload)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "${payload.targetType}_notification")
            putExtra("conversation_id", payload.conversationId)
            putExtra("message_id", payload.messageId)
            payload.senderId?.let { putExtra("sender_id", it) }
            putNotificationAccountTarget(payload.notificationAccountTarget, payload.conversationTitle)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            payload.notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, payload.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(payload.conversationTitle.ifBlank { payload.senderName.ifBlank { "Conectate" } })
            .setContentText(payload.messageBody.ifBlank { "Nuevo mensaje" })
            .setSubText(payload.senderName.takeIf { payload.isGroupConversation && it.isNotBlank() })
            .setLargeIcon(payload.largeIcon)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureChannel(
        context: Context,
        payload: MessageNotificationPayload
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            payload.channelId,
            payload.channelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = payload.channelDescription
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}
