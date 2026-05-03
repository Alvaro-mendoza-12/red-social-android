package conectate.com.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import conectate.com.MainActivity
import conectate.com.R
import conectate.com.data.service.MessageNotificationActionReceiver

data class MessageNotificationPayload(
    val channelId: String,
    val channelName: String,
    val channelDescription: String,
    val conversationId: String,
    val targetType: String,
    val conversationTitle: String,
    val senderName: String,
    val messageBody: String,
    val notificationId: Int,
    val senderId: String? = null,
    val messageId: String? = null,
    val largeIcon: Bitmap? = null,
    val isGroupConversation: Boolean = false,
    val notificationAccountTarget: NotificationAccountTarget? = null,
    val allowInlineActions: Boolean = true
)

object MessageNotificationFactory {
    fun build(context: Context, payload: MessageNotificationPayload): Notification {
        ensureChannel(
            context = context,
            channelId = payload.channelId,
            channelName = payload.channelName,
            channelDescription = payload.channelDescription
        )

        val contentIntent = PendingIntent.getActivity(
            context,
            (payload.notificationId.toString() + payload.targetType).hashCode(),
            buildOpenIntent(context, payload),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val replyAction = if (payload.allowInlineActions) {
            buildReplyAction(context, payload)
        } else {
            null
        }
        val markReadAction = if (payload.allowInlineActions) {
            buildSimpleAction(
                context = context,
                payload = payload,
                action = MessageNotificationContract.ACTION_MARK_AS_READ,
                title = "Marcar como leído"
            )
        } else {
            null
        }
        val muteAction = if (payload.allowInlineActions) {
            buildSimpleAction(
                context = context,
                payload = payload,
                action = MessageNotificationContract.ACTION_MUTE,
                title = "Silenciar"
            )
        } else {
            null
        }

        val sender = Person.Builder()
            .setName(payload.senderName.ifBlank { "Usuario" })
            .setKey(payload.senderId ?: payload.conversationId)
            .apply {
                payload.largeIcon?.let { setIcon(IconCompat.createWithBitmap(it)) }
            }
            .build()

        val style = if (payload.isGroupConversation) {
            NotificationCompat.MessagingStyle(sender)
                .addMessage(payload.messageBody, System.currentTimeMillis(), sender)
                .setConversationTitle(payload.conversationTitle.ifBlank { "Conversación" })
                .setGroupConversation(true)
        } else {
            NotificationCompat.MessagingStyle(sender)
                .addMessage(payload.messageBody, System.currentTimeMillis(), sender)
        }

        val previewText = if (payload.isGroupConversation) {
            "${payload.senderName.ifBlank { "Alguien" }}: ${payload.messageBody}"
        } else {
            payload.messageBody
        }
        val accountLabel = payload.notificationAccountTarget
            ?.presentableLabel(
                payload.notificationAccountTarget.email?.substringBefore('@')
            )

        return NotificationCompat.Builder(context, payload.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (payload.isGroupConversation) {
                    payload.conversationTitle.ifBlank { "Conversación" }
                } else {
                    payload.senderName.ifBlank { "Mensaje" }
                }
            )
            .setContentText(previewText)
            .setSubText(accountLabel?.let { "Cuenta: $it" } ?: context.getString(R.string.app_name))
            .setStyle(style)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setColor(ContextCompat.getColor(context, R.color.red_primary))
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .apply {
                payload.largeIcon?.let { setLargeIcon(it) }
                replyAction?.let(::addAction)
                markReadAction?.let(::addAction)
                muteAction?.let(::addAction)
            }
            .build()
    }

    private fun buildReplyAction(
        context: Context,
        payload: MessageNotificationPayload
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(MessageNotificationContract.KEY_TEXT_REPLY)
            .setLabel("Responder")
            .build()

        val replyIntent = buildActionIntent(
            context = context,
            payload = payload,
            action = MessageNotificationContract.ACTION_REPLY
        )

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            (payload.notificationId.toString() + "reply").hashCode(),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            "Responder",
            replyPendingIntent
        ).addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun buildSimpleAction(
        context: Context,
        payload: MessageNotificationPayload,
        action: String,
        title: String
    ): NotificationCompat.Action {
        val actionIntent = buildActionIntent(context, payload, action)
        val actionPendingIntent = PendingIntent.getBroadcast(
            context,
            (payload.notificationId.toString() + action).hashCode(),
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            title,
            actionPendingIntent
        ).build()
    }

    private fun buildActionIntent(
        context: Context,
        payload: MessageNotificationPayload,
        action: String
    ): Intent {
        return Intent(context, MessageNotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(MessageNotificationContract.EXTRA_CONVERSATION_ID, payload.conversationId)
            putExtra(MessageNotificationContract.EXTRA_TARGET_TYPE, payload.targetType)
            putExtra(MessageNotificationContract.EXTRA_SENDER_ID, payload.senderId)
            putExtra(MessageNotificationContract.EXTRA_MESSAGE_ID, payload.messageId)
            putExtra(MessageNotificationContract.EXTRA_NOTIFICATION_ID, payload.notificationId)
            putExtra(MessageNotificationContract.EXTRA_CONVERSATION_TITLE, payload.conversationTitle)
        }.putNotificationAccountTarget(
            payload.notificationAccountTarget,
            payload.notificationAccountTarget?.presentableLabel(payload.conversationTitle)
        )
    }

    private fun buildOpenIntent(
        context: Context,
        payload: MessageNotificationPayload
    ): Intent {
        return Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("chat_id", payload.conversationId)
            putExtra("sender_id", payload.senderId)
            putExtra("sender_name", payload.senderName)
            putExtra("conversation_title", payload.conversationTitle)
            putExtra("notification_preview", payload.messageBody)
            payload.messageId?.let { putExtra("message_id", it) }
            putExtra("target_type", payload.targetType)
            putExtra("type", "new_message")
            when (payload.targetType) {
                "group" -> {
                    putExtra("group_id", payload.conversationId)
                    putExtra("is_group", true)
                }
                "community" -> {
                    putExtra("community_id", payload.conversationId)
                    putExtra("is_community", true)
                }
            }
        }.putNotificationAccountTarget(
            payload.notificationAccountTarget,
            payload.notificationAccountTarget?.presentableLabel(payload.conversationTitle)
        ).also { TrustedNavigationIntents.markTrusted(context, it) }
    }

    private fun ensureChannel(
        context: Context,
        channelId: String,
        channelName: String,
        channelDescription: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = channelDescription
            enableVibration(true)
            setShowBadge(true)
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            setSound(soundUri, audioAttributes)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
