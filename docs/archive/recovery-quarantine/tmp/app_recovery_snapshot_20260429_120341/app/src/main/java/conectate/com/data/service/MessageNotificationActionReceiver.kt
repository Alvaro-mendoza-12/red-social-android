package conectate.com.data.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import conectate.com.data.repository.CommunityRepository
import conectate.com.data.repository.GroupRepository
import conectate.com.data.repository.MessageRepository
import conectate.com.data.supabase.AuthService
import conectate.com.util.ConversationNotificationPrefs
import conectate.com.util.MessageNotificationContract
import conectate.com.util.notificationAccountTargetFromIntent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MessageNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    MessageNotificationReceiverEntryPoint::class.java
                )
                var messageRepository = entryPoint.messageRepository()
                var groupRepository = entryPoint.groupRepository()
                var communityRepository = entryPoint.communityRepository()
                val authService = entryPoint.authService()
                val notificationPrefs = ConversationNotificationPrefs(appContext)

                var conversationId = intent.getStringExtra(MessageNotificationContract.EXTRA_CONVERSATION_ID)
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
                var targetType = intent.getStringExtra(MessageNotificationContract.EXTRA_TARGET_TYPE)
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()
                val notificationId = intent.getIntExtra(
                    MessageNotificationContract.EXTRA_NOTIFICATION_ID,
                    conversationId.hashCode()
                )
                val notificationAccountTarget = notificationAccountTargetFromIntent(intent)
                val isTargetingActiveAccount = notificationAccountTarget
                    ?.matches(authService.getCurrentUserId(), authService.getCurrentUserEmail())
                    ?: true

                if (conversationId.isBlank() || targetType.isBlank() || !isTargetingActiveAccount) {
                    cancelNotification(appContext, notificationId)
                    return@launch
                }

                when (intent.action) {
                    MessageNotificationContract.ACTION_REPLY -> {
                        var replyText = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(MessageNotificationContract.KEY_TEXT_REPLY)
                            ?.toString()
                            ?.trim()
                            .orEmpty()
                        if (replyText.isNotBlank()) {
                            handleReply(
                                targetType = targetType,
                                conversationId = conversationId,
                                replyText = replyText,
                                messageRepository = messageRepository,
                                groupRepository = groupRepository,
                                communityRepository = communityRepository
                            )
                            handleMarkRead(
                                targetType = targetType,
                                conversationId = conversationId,
                                messageRepository = messageRepository,
                                groupRepository = groupRepository,
                                communityRepository = communityRepository
                            )
                        }
                    }

                    MessageNotificationContract.ACTION_MARK_AS_READ -> {
                        handleMarkRead(
                            targetType = targetType,
                            conversationId = conversationId,
                            messageRepository = messageRepository,
                            groupRepository = groupRepository,
                            communityRepository = communityRepository
                        )
                    }

                    MessageNotificationContract.ACTION_MUTE -> {
                        notificationPrefs.muteConversation(targetType, conversationId)
                        if (targetType == "chat") {
                            runCatching {
                                messageRepository.muteChat(conversationId, true)
                            }
                        }
                    }
                }

                cancelNotification(appContext, notificationId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleReply(
        targetType: String,
        conversationId: String,
        replyText: String,
        messageRepository: MessageRepository,
        groupRepository: GroupRepository,
        communityRepository: CommunityRepository
    ) {
        when (targetType) {
            "chat" -> {
                messageRepository.sendMessage(chatId = conversationId, content = replyText)
            }

            "group" -> {
                groupRepository.sendGroupMessage(groupId = conversationId, content = replyText)
            }

            "community" -> {
                communityRepository.sendCommunityMessage(communityId = conversationId, content = replyText)
            }
        }
    }

    private suspend fun handleMarkRead(
        targetType: String,
        conversationId: String,
        messageRepository: MessageRepository,
        groupRepository: GroupRepository,
        communityRepository: CommunityRepository
    ) {
        when (targetType) {
            "chat" -> messageRepository.markChatAsRead(conversationId)
            "group" -> groupRepository.markAsRead(conversationId)
            "community" -> communityRepository.markAsRead(conversationId)
        }
    }

    private fun cancelNotification(context: Context, notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.cancel(notificationId)
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MessageNotificationReceiverEntryPoint {
    fun messageRepository(): MessageRepository
    fun groupRepository(): GroupRepository
    fun communityRepository(): CommunityRepository
    fun authService(): AuthService
}



