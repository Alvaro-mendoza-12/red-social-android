package conectate.com.data.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import conectate.com.util.ConversationNotificationIdentity
import conectate.com.util.ConversationNotificationPrefs
import conectate.com.util.MessageNotificationContract
import conectate.com.util.MessageNotificationFactory
import conectate.com.util.NotificationAccountTarget
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Durable direct replies use repository outboxes and a stable id across retries. */
class MessageNotificationActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, MessageNotificationReceiverEntryPoint::class.java)
        val auth = deps.authService()
        val activeAccountId = auth.getCurrentUserId()
        val account = NotificationAccountTarget(userId = inputData.getString("account_id"), email = inputData.getString("account_email"))
        val type = inputData.getString(MessageNotificationContract.EXTRA_TARGET_TYPE).orEmpty()
        val id = inputData.getString(MessageNotificationContract.EXTRA_CONVERSATION_ID).orEmpty()
        val channel = inputData.getString(MessageNotificationContract.EXTRA_CHANNEL_ID)
        val action = inputData.getString("action")
        val scope = ConversationNotificationIdentity.scope(account, type, id, channel)
        val notificationId = inputData.getInt(MessageNotificationContract.EXTRA_NOTIFICATION_ID, id.hashCode())
        if (!account.isDefined || !account.matches(activeAccountId, auth.getCurrentUserEmail()) ||
            type !in setOf("chat", "group", "community") || id.isBlank()) return Result.failure()
        if (action == MessageNotificationContract.ACTION_REPLY &&
            (!ConversationNotificationIdentity.canReply(inputData.getString(MessageNotificationContract.KEY_TEXT_REPLY).orEmpty()) || inputData.getString("reply_id").isNullOrBlank())) return Result.failure()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        try {
            withTimeout(30_000L) {
                when (action) {
                    MessageNotificationContract.ACTION_REPLY -> {
                        val text = inputData.getString(MessageNotificationContract.KEY_TEXT_REPLY).orEmpty()
                        if (!ConversationNotificationIdentity.canReply(text)) return@withTimeout
                        var queued = false
                        val stableId = inputData.getString("reply_id") ?: return@withTimeout
                        val accepted = when (type) {
                            "chat" -> deps.messageRepository().sendMessage(id, text, messageId = stableId, onQueued = { queued = true }).isSuccess
                            "group" -> deps.groupRepository().sendGroupMessage(id, text, messageId = stableId, onQueued = { queued = true }).isSuccess
                            else -> deps.communityRepository().sendCommunityMessage(id, text, channelId = channel, messageId = stableId, onQueued = { queued = true }).isSuccess
                        }
                        check(accepted || queued) { "Reply was not accepted by the repository" }
                        // The outbox owns delivery retries. Do not send a second copy here.
                    }
                    MessageNotificationContract.ACTION_MARK_AS_READ -> when (type) {
                        "chat" -> deps.messageRepository().markChatAsRead(id).getOrThrow()
                        "group" -> deps.groupRepository().markAsRead(id, requireRemoteConfirmation = true, expectedAccountId = activeAccountId)
                        else -> deps.communityRepository().markAsRead(id, requireRemoteConfirmation = true, expectedAccountId = activeAccountId)
                    }
                    MessageNotificationContract.ACTION_MUTE -> {
                        ConversationNotificationPrefs(applicationContext).muteConversationUntil(type, id, System.currentTimeMillis() + 8 * 60 * 60 * 1000L, account.userId)
                    }
                    else -> throw IllegalArgumentException("Unknown notification action")
                }
            }
            // A message arriving while the action runs remains visible.
            MessageNotificationFactory.finishAction(applicationContext, scope,
                inputData.getString(MessageNotificationContract.EXTRA_MESSAGE_ID), notificationId)
            return Result.success()
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            MessageNotificationFactory.finishAction(applicationContext, scope,
                inputData.getString(MessageNotificationContract.EXTRA_MESSAGE_ID), notificationId,
                "No se pudo completar. Abre la conversación para reintentar.")
            return Result.failure()
        }
    }
}
