package conectate.com.data.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import conectate.com.data.sync.PendingMessageSyncScheduler
import conectate.com.util.ConversationNotificationIdentity
import conectate.com.util.ConversationNotificationPrefs
import conectate.com.util.MessageNotificationContract
import conectate.com.util.MessageNotificationFactory
import conectate.com.util.NotificationAccountTarget
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private const val ACTION_TIMEOUT_MILLIS = 30_000L

internal suspend fun acceptNotificationReply(
    send: suspend (onQueued: suspend () -> Unit) -> Result<*>,
    resumePendingDelivery: () -> Unit,
    timeoutMillis: Long = ACTION_TIMEOUT_MILLIS,
): Boolean {
    var queued = false
    val accepted = withTimeoutOrNull(timeoutMillis) { send { queued = true }.isSuccess }
    if (accepted == null && queued) resumePendingDelivery()
    return queued || accepted == true
}

/** Durable direct replies use repository outboxes and a stable id across retries. */
class MessageNotificationActionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, MessageNotificationReceiverEntryPoint::class.java)
        val auth = deps.authService()
        val accountLease = auth.captureAccountLease()
        val activeAccountId = accountLease.accountId ?: return Result.failure()
        val account = NotificationAccountTarget(userId = inputData.getString("account_id"), email = inputData.getString("account_email"))
        val type = inputData.getString(MessageNotificationContract.EXTRA_TARGET_TYPE).orEmpty()
        val id = inputData.getString(MessageNotificationContract.EXTRA_CONVERSATION_ID).orEmpty()
        val channel = inputData.getString(MessageNotificationContract.EXTRA_CHANNEL_ID)
        val action = inputData.getString("action")
        val scope = ConversationNotificationIdentity.scope(account, type, id, channel)
        val notificationId = inputData.getInt(MessageNotificationContract.EXTRA_NOTIFICATION_ID, id.hashCode())
        if (!account.isDefined || !account.matches(activeAccountId, auth.getCurrentUserEmail()) || !accountLease.isCurrent() ||
            type !in setOf("chat", "group", "community") || id.isBlank()) return Result.failure()
        try {
            if (action == MessageNotificationContract.ACTION_REPLY) {
                val text = inputData.getString(MessageNotificationContract.KEY_TEXT_REPLY).orEmpty()
                val stableId = inputData.getString("reply_id")?.takeIf(String::isNotBlank) ?: return Result.failure()
                if (!ConversationNotificationIdentity.canReply(text)) return Result.failure()
                val accepted = acceptNotificationReply(
                    send = { onQueued ->
                        when (type) {
                            "chat" -> deps.messageRepository().sendMessage(accountLease, id, text, messageId = stableId, onQueued = onQueued)
                            "group" -> deps.groupRepository().sendGroupMessage(accountLease, id, text, messageId = stableId, onQueued = onQueued)
                            else -> deps.communityRepository().sendCommunityMessage(accountLease, id, text, channelId = channel, messageId = stableId, onQueued = onQueued)
                        }
                    },
                    // The deadline can cancel the repository between the durable save and scheduling its outbox.
                    resumePendingDelivery = {
                        accountLease.requireCurrent()
                        PendingMessageSyncScheduler.enqueue(applicationContext, "notification_reply_pending")
                    },
                )
                check(accepted) { "Reply was not accepted by the repository" }
            } else {
                withTimeout(ACTION_TIMEOUT_MILLIS) {
                    accountLease.requireCurrent()
                    when (action) {
                        MessageNotificationContract.ACTION_MARK_AS_READ -> when (type) {
                            "chat" -> deps.messageRepository().markChatAsRead(accountLease, id)
                            "group" -> deps.groupRepository().markAsRead(id, requireRemoteConfirmation = true, expectedAccountId = activeAccountId)
                            else -> deps.communityRepository().markAsRead(id, requireRemoteConfirmation = true, expectedAccountId = activeAccountId)
                        }
                        MessageNotificationContract.ACTION_MUTE -> {
                            val muted = auth.commitForAccountLease(accountLease) {
                                ConversationNotificationPrefs(applicationContext).muteConversationUntil(
                                    type, id, System.currentTimeMillis() + 8 * 60 * 60 * 1000L, activeAccountId,
                                )
                            }
                            if (!muted) throw CancellationException("Notification account changed")
                        }
                        else -> throw IllegalArgumentException("Unknown notification action")
                    }
                }
            }
            accountLease.requireCurrent()
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
