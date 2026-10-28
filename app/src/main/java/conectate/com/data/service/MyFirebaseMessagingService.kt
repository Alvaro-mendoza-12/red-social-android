package conectate.com.data.service

import kotlin.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.RingtoneManager
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import conectate.com.MainActivity
import conectate.com.R
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.repository.AccountSessionRepository
import conectate.com.data.repository.CallRepository
import conectate.com.data.repository.MultiAccountInboxRepository
import conectate.com.data.repository.NotificationRepository
import conectate.com.data.local.prefs.AppSettingsPreferences
import conectate.com.data.repository.message.MessageSessionStateStore
import conectate.com.data.model.MultiAccountPendingDestination
import conectate.com.data.supabase.AuthService
import conectate.com.util.buildConversationBackgroundPreview
import conectate.com.util.ConversationNotificationPrefs
import conectate.com.util.isCallEventContentType
import conectate.com.util.MessageNotificationFactory
import conectate.com.util.MessageNotificationPayload
import conectate.com.util.NotificationAccountTarget

import conectate.com.util.notificationAccountTargetFromRemoteData
import conectate.com.util.putNotificationAccountTarget
import conectate.com.util.resolveStoredAccountForTarget
import conectate.com.util.storedAccountDisplayLabel
import conectate.com.services.IncomingCallForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import java.io.ByteArrayOutputStream

@AndroidEntryPoint
class MyFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var notificationRepository: NotificationRepository

    @Inject
    lateinit var messageRepository: conectate.com.data.repository.MessageRepository

    @Inject
    lateinit var groupRepository: conectate.com.data.repository.GroupRepository

    @Inject
    lateinit var communityRepository: conectate.com.data.repository.CommunityRepository

    @Inject
    lateinit var callRepositoryProvider: dagger.Lazy<CallRepository>

    private val callRepository: CallRepository
        get() = callRepositoryProvider.get()

    @Inject
    lateinit var authService: AuthService

    @Inject
    lateinit var accountSessionRepository: AccountSessionRepository

    @Inject
    lateinit var multiAccountInboxRepository: MultiAccountInboxRepository

    @Inject
    lateinit var messageSessionStateStore: MessageSessionStateStore

    @Inject
    lateinit var appSettingsPreferences: AppSettingsPreferences

    @Inject lateinit var notificationUserRepository: conectate.com.data.repository.UserRepository

    @Inject lateinit var notificationGroupDao: conectate.com.data.local.dao.GroupDao
    @Inject lateinit var notificationCommunityDao: conectate.com.data.local.dao.CommunityDao
    @Inject lateinit var conversationReadReceiver: conectate.com.data.repository.message.ConversationReadReceiver

    private val serviceScope = newSafeScope("FirebaseMessagingService", Dispatchers.IO)
    private val callCancellationGuard by lazy { CallCancellationGuard(this) }

    override fun onNewToken(token: String) {
        android.util.Log.d(TAG, "FCM token refreshed successfully")
        
        serviceScope.launch {
            try {
                notificationRepository.registerFcmToken(token)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error registering token", e)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SocialApp:NotificationProcessing")
        wakeLock.acquire(10000) // 10 seconds max
        
        try {
            val data = remoteMessage.data
            if (data.isEmpty()) return // 1. Basic Extraction
            if (conectate.com.util.isRetiredMomentViewNotification(
                    data["type"], data["notification_type"], data["notificationType"]
                )) return
            val notificationTitle = remoteMessage.notification?.title
            val notificationBody = remoteMessage.notification?.body
            
            // Parse all known target keys (snake_case + camelCase) to avoid notification misrouting.
            var groupId = data["group_id"] ?: data["groupId"]
            var communityId = data["community_id"] ?: data["communityId"]
            var chatId = data["chat_id"] ?: data["chatId"] ?: data["challenge_id"]
            var type = data["type"] ?: "text"
            var contentType = data["content_type"] ?: "text"
            var senderId = data["sender_id"] ?: data["senderId"] ?: data["uid"]
            var senderName = data["sender_name"] ?: data["senderName"] ?: data["author_name"] ?: notificationTitle ?: "Novvin"
            var content = data["content"] ?: data["message"] ?: data["body"] ?: data["text"] ?: data["comment"] ?: notificationBody ?: ""
            val displayContent = conectate.com.util.typedMessageNotificationPreview(contentType)
                ?: buildConversationBackgroundPreview(contentType, content) ?: content
            var targetId = data["target_id"] ?: data["targetId"] ?: data["post_id"] ?: data["video_id"] ?: data["challenge_id"] ?: ""
            var postId = data["post_id"] ?: data["postId"]
            var videoId = data["video_id"] ?: data["videoId"]
            var messageId = data["message_id"] ?: data["focus_message_id"]
            var commentId = data["comment_id"] ?: data["focus_comment_id"]
            
            var isGroup = data["is_group"] == "true" ||
                data["isGroup"] == "true" ||
                !groupId.isNullOrBlank()
            var isCommunity = data["is_community"] == "true" ||
                data["isCommunity"] == "true" ||
                !communityId.isNullOrBlank()
            val remoteAccountTarget = notificationAccountTargetFromRemoteData(data)
            val currentAccountTarget = currentNotificationAccountTarget()
            if (!conectate.com.util.canReceiveAccountNotification(
                    remoteAccountTarget,
                    authService.getCurrentUserId(),
                    authService.getCurrentUserEmail(),
                    authService.isLoggedIn(),
                    accountSessionRepository.accounts.value
                )) return
            val effectiveAccountTarget = remoteAccountTarget ?: currentAccountTarget
            val isTargetingActiveAccount = remoteAccountTarget
                ?.matches(authService.getCurrentUserId(), authService.getCurrentUserEmail())
                ?: true
            val storedTargetAccount = resolveStoredAccountForTarget(
                effectiveAccountTarget,
                accountSessionRepository.accounts.value
            )
            val targetAccountLabel = effectiveAccountTarget
                ?.presentableLabel(storedTargetAccount?.let(::storedAccountDisplayLabel))

            android.util.Log.d(
                TAG,
                "Notification received type=$type chatId=${chatId ?: "-"} groupId=${groupId ?: "-"} communityId=${communityId ?: "-"}"
            )

            // Always prioritize explicit group/community targets over generic chat_id.
            if (isCommunity && !communityId.isNullOrBlank()) {
                chatId = communityId
            } else if (isGroup && !groupId.isNullOrBlank()) {
                chatId = groupId
            } else if (type == "new_message" && chatId.isNullOrBlank() && targetId.isNotBlank()) {
                chatId = targetId
            }
            
            android.util.Log.d(
                TAG,
                "Extraction: chatId=$chatId, groupId=$groupId, communityId=$communityId, isGroup=$isGroup, isCommunity=$isCommunity"
            )

            if (type == "conversation_read") {
                val event = conectate.com.util.conversationReadEvent(data) ?: return
                if (event.accountId != authService.getCurrentUserId()?.trim()?.lowercase() &&
                    accountSessionRepository.accounts.value.none { it.user_id?.trim()?.lowercase() == event.accountId }) return
                runBlocking(Dispatchers.IO) { conversationReadReceiver.apply(event) }
                serviceScope.launch { multiAccountInboxRepository.refreshInactiveAccounts() }
                return
            }
            // Deletions are data-only control events, processed before hints, repository inserts and alerts.
            if (type.trim().equals("message_deleted", true)) {
                val event = conectate.com.util.deletedMessageEvent(data) ?: return
                val recipient = event.identity.accountId
                val current = authService.getCurrentUserId()?.trim()?.lowercase()
                val isKnownRecipient = recipient == current || accountSessionRepository.accounts.value.any {
                    it.user_id?.trim()?.lowercase() == recipient
                }
                if (!isKnownRecipient) return
                MessageNotificationFactory.redactMessage(this, event.identity, event.createdAt, channelContextId = event.channelId)
                multiAccountInboxRepository.redactDeletedMessageHint(event.identity)
                if (recipient == current) runBlocking(Dispatchers.IO) {
                    when (event.identity.targetType) {
                        "group" -> groupRepository.handleDeletedMessage(event.identity, event.createdAt, event.channelId)
                        "community" -> communityRepository.handleDeletedMessage(event.identity, event.createdAt, event.channelId)
                        else -> messageRepository.handleDeletedMessage(event.identity, event.createdAt, event.channelId)
                    }
                }
                return
            }
            val incomingIdentity = conectate.com.util.DeletedMessageIdentity.create(
                effectiveAccountTarget?.userId, if (isCommunity) "community" else if (isGroup) "group" else "chat", chatId, messageId)
            if (incomingIdentity != null && conectate.com.util.DeletedMessageStore(this).isSuppressed(incomingIdentity)) return
            if (conectate.com.util.shouldRecordUnreadPush(type,messageId,senderId,effectiveAccountTarget?.userId) &&
                conectate.com.util.ConversationReadStore(this).isRead(effectiveAccountTarget?.userId,
                    if (isCommunity) "community" else if (isGroup) "group" else "chat",chatId,messageId,data["created_at"])) return

            if (type == "message_reaction") {
                val scopeType = if (isCommunity) "community" else if (isGroup) "group" else "chat"
                val conversationId = chatId?.trim()?.takeIf { it.isNotBlank() } ?: return
                if (!appSettingsPreferences.messageNotificationsEnabled.value) return
                if (isTargetingActiveAccount && (messageSessionStateStore.isActiveConversationVisible(scopeType, conversationId) ||
                    ConversationNotificationPrefs(this).isMuted(scopeType, conversationId, authService.getCurrentUserId()))) return
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
                MessageNotificationFactory.postReaction(this, MessageNotificationPayload(
                    channelId = if (scopeType == "chat") getString(R.string.chat_notification_channel_id) else getString(R.string.group_notification_channel_id),
                    channelName = "Reacciones a mensajes", channelDescription = "Avisos de reacciones recibidas",
                    conversationId = conversationId, targetType = scopeType, conversationTitle = data["conversation_title"] ?: "Conversación",
                    senderName = senderName, messageBody = data["body"] ?: content,
                    notificationId = ("reaction:$conversationId:$senderId").hashCode(), senderId = senderId, messageId = messageId,
                    notificationAccountTarget = effectiveAccountTarget, isGroupConversation = scopeType != "chat", allowInlineActions = false,
                    channelContextId = data["channel_id"]
                ))
                return
            }

            if (!isTargetingActiveAccount && !chatId.isNullOrBlank() &&
                conectate.com.util.shouldRecordUnreadPush(type, messageId, senderId, effectiveAccountTarget?.userId)) {
                val pendingTargetType = when {
                    isCommunity -> "community"
                    isGroup -> "group"
                    else -> "chat"
                }
                val pendingTitle = when {
                    isCommunity -> data["community_name"] ?: data["communityName"] ?: data["title"]
                    isGroup -> data["group_name"] ?: data["groupName"] ?: data["title"]
                    else -> senderName
                }
                multiAccountInboxRepository.registerUnreadConversationHint(
                    target = effectiveAccountTarget,
                    destination = MultiAccountPendingDestination(
                        targetType = pendingTargetType,
                        conversationId = chatId,
                        userId = senderId,
                        conversationTitle = pendingTitle,
                        senderName = senderName,
                        preview = displayContent,
                        focusMessageId = messageId
                    )
                )
            }

            // 2. INCOMING CALLS (full screen)
            if (type.trim().equals("call_invite", ignoreCase = true)) {
                var callId = (data["call_id"] ?: data["callId"] ?: targetId).trim()
                val callTitle = (data["call_title"] ?: data["callTitle"] ?: senderName).trim().ifBlank { senderName }
                var contextType = (data["call_context_type"] ?: data["context_type"] ?: "dm").trim().ifBlank { "dm" }
                var contextId = (data["call_context_id"] ?: data["context_id"]).orEmpty().trim().ifBlank { null }
                val callerAvatar = data["sender_avatar"] ?: data["senderAvatar"]
                val callCreatedAt = data["call_created_at"] ?: data["callCreatedAt"]
                val isVideoCall = listOf(
                    data["call_is_video"],
                    data["is_video"],
                    data["isVideo"]
                ).any { it.equals("true", ignoreCase = true) || it == "1" }

                if (callId.isBlank() || senderId.isNullOrBlank()) return
                if (wasCallRecentlyCancelled(callId)) {
                    android.util.Log.i(TAG, "Ignoring late call_invite for cancelled callId=$callId")
                    return
                }
                if (isStaleCallInvite(callCreatedAt)) {
                    android.util.Log.i(TAG, "Ignoring stale call_invite for callId=$callId")
                    markCallRecentlyCancelled(callId)
                    return
                }

                val invite = conectate.com.data.model.CallInvite(
                    callId = callId,
                    title = callTitle,
                    callerUserId = senderId,
                    callerName = senderName,
                    callerAvatarUrl = callerAvatar,
                    contextType = contextType,
                    contextId = contextId,
                    isVideo = isVideoCall
                )

                val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                var duplicateAlreadyPresented = false
                if (isTargetingActiveAccount) {
                    when (callRepository.incomingCallDisposition(invite)) {
                        conectate.com.data.repository.IncomingCallDisposition.PRESENT -> {
                            callRepository.presentIncomingInvite(invite)
                        }
                        conectate.com.data.repository.IncomingCallDisposition.IGNORE_DUPLICATE -> {
                            duplicateAlreadyPresented = true
                            if (isForeground) return
                        }
                        conectate.com.data.repository.IncomingCallDisposition.REJECT_BUSY -> {
                            android.util.Log.i(TAG, "User busy in another call. Auto-declining callId=$callId")
                            serviceScope.launch {
                                callRepository.rejectIncomingWhileBusy(invite).onFailure { error ->
                                    android.util.Log.w(TAG, "Busy response failed for callId=$callId", error)
                                }
                            }
                            return
                        }
                        conectate.com.data.repository.IncomingCallDisposition.YIELD_OUTGOING -> {
                            if (!callRepository.yieldOutgoingCallToIncoming(invite)) {
                                serviceScope.launch { callRepository.rejectIncomingWhileBusy(invite) }
                                return
                            }
                            callRepository.presentIncomingInvite(invite)
                        }
                    }
                }

                android.util.Log.i(
                    TAG,
                    "Received call_invite via FCM: callId=$callId sender=$senderId foreground=$isForeground duplicate=$duplicateAlreadyPresented"
                )
                if (isForeground && isTargetingActiveAccount) {
                    // The repository state already drives the in-app incoming UI.
                } else {
                    if (!appSettingsPreferences.callNotificationsEnabled.value) {
                        android.util.Log.d(TAG, "Call notifications disabled by user preference.")
                        return
                    }
                    sendIncomingCallNotification(invite, effectiveAccountTarget)
                }
                return
            }

            if (type.trim().equals("call_cancel", ignoreCase = true)) {
                var callId = (data["call_id"] ?: data["callId"] ?: targetId).trim()
                if (callId.isNotBlank()) {
                    val action = (data["call_action"] ?: data["callAction"] ?: "cancel").trim()
                    val cancelsPendingIncoming = callRepository.incomingInvite.value
                        ?.callId
                        ?.equals(callId, ignoreCase = true) == true
                    callRepository.handleExternalCallResponse(callId, action, senderId.orEmpty())
                    markCallRecentlyCancelled(callId)
                    var notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(callId.hashCode())
                    // A late cancel for a losing simultaneous call must never stop
                    // the different call that won collision resolution.
                    IncomingCallForegroundService.stop(this, callId)
                    if (isTargetingActiveAccount && cancelsPendingIncoming) {
                        callRepository.dismissIncomingInvite()
                    }
                }
                return
            }

            if (type.trim().equals("call_response", ignoreCase = true)) {
                val callId = (data["call_id"] ?: data["callId"] ?: targetId).trim()
                val action = (data["call_action"] ?: data["callAction"]).orEmpty().trim()
                if (callId.isNotBlank() && action.isNotBlank()) {
                    callRepository.handleExternalCallResponse(callId, action, senderId.orEmpty())
                }
                return
            }

            if (type.trim().equals("live_started", ignoreCase = true)) {
                if (!appSettingsPreferences.liveNotificationsEnabled.value) {
                    android.util.Log.d(TAG, "Live notifications disabled by user preference.")
                    return
                }

                val liveId = (
                    data["live_id"] ?: data["liveId"] ?: data["target_id"] ?: data["targetId"]
                ).orEmpty().trim()
                val liveTitle = data["title"]?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "$senderName inició un directo"
                val liveBody = data["body"]?.trim().takeUnless { it.isNullOrBlank() }
                    ?: data["message"]?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "Está en vivo ahora."

                if (liveId.isBlank()) {
                    // A malformed event must never create a privileged navigation intent.
                    sendSystemNotification(liveTitle, liveBody)
                    return
                }

                sendLiveStartedNotification(
                    title = liveTitle,
                    body = liveBody,
                    liveId = liveId,
                    hostId = data["host_id"] ?: data["hostId"] ?: senderId,
                    hostAvatar = data["sender_avatar"]
                        ?: data["senderAvatar"]
                        ?: data["actor_avatar_url"]
                        ?: data["host_avatar_url"],
                    notificationAccountTarget = effectiveAccountTarget,
                    accountLabel = targetAccountLabel
                )
                return
            }

            // 2. SOCIAL & VIDEOS (Highest Priority Fix)
            val socialTypes = listOf(
                "like", "like_post", "like_video", "follow", "friend_request", "friend_request_accepted",
                "comment", "comment_post", "reply_post", "comment_video", "reply_video",
                "mention", "mention_post", "mention_video", "share", "share_post", "share_video", "admin_warning_video",
                "challenge_invite", "voice_invite", "voice_access_request", "birthday", "new_moment", "like_moment"
            )

            if (type in socialTypes) {
                if (
                    type == "voice_invite" &&
                    !appSettingsPreferences.liveNotificationsEnabled.value
                ) {
                    android.util.Log.d(TAG, "Live notifications disabled by user preference: $type")
                    return
                }
                if (!appSettingsPreferences.socialNotificationsEnabled.value) {
                    android.util.Log.d(TAG, "Social notifications disabled by user preference: $type")
                    return
                }
                var isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                if (isForeground && isTargetingActiveAccount) {
                    android.util.Log.d(TAG, "Skipping foreground social system notification for active account: $type")
                    return
                }
                android.util.Log.d(TAG, "Routing to SOCIAL: $type")
                sendSocialNotification(
                    title = data["title"] ?: senderName,
                    body = data["body"] ?: displayContent,
                    type = type,
                    targetId = targetId,
                    postId = postId,
                    videoId = videoId,
                    senderId = senderId ?: "",
                    senderAvatar = data["sender_avatar"],
                    commentId = commentId,
                    notificationId = data["notification_id"],
                    expiresAt = data["expires_at"],
                    notificationAccountTarget = effectiveAccountTarget,
                    accountLabel = targetAccountLabel
                )
                return // DONE
            }

            // 3. Inject into Repository for the matching active session only.
            if (isTargetingActiveAccount && !chatId.isNullOrBlank()) {
                // La notificación no debe adelantarse a la fila local: si el
                // usuario la toca inmediatamente, el mensaje ya debe existir.
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(2_000L) {
                    try {
                        val incomingId = messageId ?: "fcm_${System.currentTimeMillis()}"
                        if (isGroup) {
                            runCatching { groupRepository.markMessageAsDelivered(chatId, incomingId) }
                            var msg = conectate.com.data.model.GroupMessage(
                                id = incomingId,
                                group_id = chatId,
                                sender_id = senderId ?: "unknown",
                                content = content,
                                content_type = contentType,
                                media_url = data["media_url"] ?: data["url"],
                                created_at = data["created_at"] ?: java.time.Instant.now().toString(),
                                sender_name = senderName,
                                reply_to_id = data["reply_to_id"],
                                poll_id = data["poll_id"],
                                is_forwarded = data["is_forwarded"]?.toBooleanStrictOrNull() ?: false,
                                forwarded_from_message_id = data["forwarded_from_message_id"],
                                forwarded_from_user_id = data["forwarded_from_user_id"]
                            )
                            groupRepository.handleIncomingMessage(msg)
                        } else if (isCommunity) {
                            runCatching { communityRepository.markMessageAsDelivered(chatId, incomingId) }
                            var msg = conectate.com.data.model.CommunityMessage(
                                id = incomingId,
                                community_id = chatId,
                                channel_id = data["channel_id"],
                                sender_id = senderId ?: "unknown",
                                content = content,
                                content_type = contentType,
                                media_url = data["media_url"] ?: data["url"],
                                created_at = data["created_at"] ?: java.time.Instant.now().toString(),
                                sender_name = senderName,
                                reply_to_id = data["reply_to_id"],
                                poll_id = data["poll_id"],
                                is_forwarded = data["is_forwarded"]?.toBooleanStrictOrNull() ?: false,
                                forwarded_from_message_id = data["forwarded_from_message_id"],
                                forwarded_from_user_id = data["forwarded_from_user_id"]
                            )
                            communityRepository.handleIncomingMessage(msg)
                        } else {
                            runCatching { messageRepository.markMessageAsDelivered(chatId, incomingId) }
                            var msg = conectate.com.data.model.Message(
                                id = incomingId,
                                chat_id = chatId,
                                sender_id = senderId ?: "unknown",
                                content = content,
                                content_type = contentType,
                                media_url = data["media_url"] ?: data["url"],
                                media_size = data["media_size"]?.toLongOrNull() ?: 0L,
                                reply_to_id = data["reply_to_id"],
                                poll_id = data["poll_id"],
                                is_forwarded = data["is_forwarded"]?.toBooleanStrictOrNull() ?: false,
                                forwarded_from_message_id = data["forwarded_from_message_id"],
                                forwarded_from_user_id = data["forwarded_from_user_id"],
                                created_at = data["created_at"] ?: java.time.Instant.now().toString(),
                                status = "delivered"
                            )
                            messageRepository.handleIncomingMessage(msg)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Background Repo Insert Error", e)
                    }
                    }
                }
            }
            
            if (isCallEventContentType(contentType)) {
                android.util.Log.d(TAG, "Skipping standard chat notification for call event contentType=$contentType")
                return
            }

            val activeConversationType = when {
                isCommunity -> "community"
                isGroup -> "group"
                else -> "chat"
            }
            if (
                isTargetingActiveAccount &&
                !chatId.isNullOrBlank() &&
                messageSessionStateStore.isActiveConversationVisible(activeConversationType, chatId)
            ) {
                MessageNotificationFactory.cancelConversation(this, effectiveAccountTarget, activeConversationType, chatId)
                android.util.Log.d(TAG, "Conversation $activeConversationType/$chatId visible; notification skipped.")
                return
            }

            if (!appSettingsPreferences.messageNotificationsEnabled.value) {
                android.util.Log.d(TAG, "Message notifications disabled by user preference.")
                return
            }

            // 4. CHAT ROUTING
            if (isGroup || isCommunity) {
                val cachedConversation = if (isTargetingActiveAccount && !chatId.isNullOrBlank()) runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(300L) { conectate.com.core.media.NotificationAvatarLoader.optionalValue {
                        if (isCommunity) notificationCommunityDao.getCommunityById(chatId)?.let { it.name to it.image }
                        else notificationGroupDao.getGroupById(chatId)?.let { it.name to it.image }
                    } }
                } else null
                val targetType = if (isCommunity) "community" else "group"
                val suppliedName = data["conversation_title"] ?: data["community_name"] ?: data["communityName"] ?: data["group_name"] ?: data["groupName"]
                val groupName = conectate.com.util.ConversationNotificationIdentity.title(targetType, suppliedName, cachedConversation?.first)
                sendGroupNotification(
                    title = groupName,
                    createdAt = data["created_at"],
                    messageBody = conectate.com.util.mentionNotificationPreview(displayContent, data["mentioned"] == "true"),
                    chatId = chatId ?: "group_default",
                    senderId = senderId,
                    senderName = senderName,
                    groupName = groupName,
                    conversationAvatar = data["conversation_avatar"] ?: data["community_avatar"] ?: data["group_avatar"] ?: cachedConversation?.second,
                    channelContextId = data["channel_id"],
                    senderAvatar = data["sender_avatar"],
                    isGroup = isGroup,
                    isCommunity = isCommunity,
                    isMention = data["mentioned"] == "true",
                    messageId = messageId,
                    notificationAccountTarget = effectiveAccountTarget,
                    accountLabel = targetAccountLabel,
                    respectMutePreferences = isTargetingActiveAccount
                )
            } else if (!chatId.isNullOrBlank() || type == "new_message") {
                if (isTargetingActiveAccount && !chatId.isNullOrBlank() && messageRepository.isChatMutedLocally(chatId)) {
                    android.util.Log.d(TAG, "Chat $chatId muted locally. Notification skipped.")
                    return
                }
                sendChatNotification(
                    createdAt = data["created_at"],
                    title = senderName,
                    messageBody = displayContent,
                    chatId = chatId,
                    senderId = senderId,
                    senderAvatar = data["sender_avatar"],
                    messageId = messageId,
                    notificationAccountTarget = effectiveAccountTarget,
                    accountLabel = targetAccountLabel,
                    allowInlineActions = isTargetingActiveAccount,
                    respectMutePreferences = isTargetingActiveAccount
                )
            } else {
                var title = data["title"] ?: data["notification_title"] ?: senderName
                var body = data["body"] ?: data["message"] ?: displayContent
                val isLiveNotification = type.startsWith("live_") ||
                    type in setOf("new_live", "stream_reminder", "stream_soon", "voice_invite")
                if (isLiveNotification && !appSettingsPreferences.liveNotificationsEnabled.value) {
                    android.util.Log.d(TAG, "Live notifications disabled by user preference: $type")
                    return
                }
                if (body.isNotBlank()) {
                    sendSystemNotification(title, body, data["notification_id"]?.takeIf { type == "system_announcement" })
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "CRITICAL ERROR processing FCM: ${e.message}", e)
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
                android.util.Log.d(TAG, "WakeLock Released.")
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun sendSocialNotification(
        title: String,
        body: String,
        type: String,
        targetId: String,
        postId: String?,
        videoId: String?,
        senderId: String,
        senderAvatar: String?,
        commentId: String?,
        notificationId: String?,
        expiresAt: String?,
        notificationAccountTarget: NotificationAccountTarget?,
        accountLabel: String?
    ) {
        var channelId = getString(R.string.social_notification_channel_id)
        val action = when (type) {
            "friend_request_accepted" -> getString(R.string.notification_friend_accepted)
            "friend_request" -> getString(R.string.notification_friend_requested)
            "follow" -> getString(R.string.notification_followed)
            else -> null
        }
        val readableBody=conectate.com.util.socialNotificationBody(title,body,action)
        var intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", type)
            notificationId?.takeIf { it.isNotBlank() }?.let { putExtra("notification_id", it) }
            commentId?.let { putExtra("comment_id", it) }
            when (type) {
                "birthday" -> putExtra("birthday_user_id", senderId.ifBlank { targetId })
                "follow", "friend_request", "friend_request_accepted", "voice_access_request" -> putExtra("follower_id", senderId.ifBlank { targetId })
                "like", "like_post", "comment", "comment_post", "reply_post", "mention", "mention_post", "share", "share_post" -> putExtra("post_id", targetId)
                "like_video", "comment_video", "reply_video", "mention_video", "share_video", "admin_warning_video" -> putExtra("video_id", targetId)
                "voice_invite" -> putExtra("room_id", targetId)
                "challenge_invite" -> putExtra("challenge_id", targetId)
                "new_moment", "like_moment" -> putExtra("moment_id", targetId)
            }
            if (type == "mention" && !videoId.isNullOrBlank()) {
                putExtra("video_id", videoId)
                putExtra("target_type", "video")
            } else if (type == "mention" && !postId.isNullOrBlank()) {
                putExtra("post_id", postId)
                putExtra("target_type", "post")
            }
        }.putNotificationAccountTarget(
            notificationAccountTarget,
            accountLabel
        ).also { conectate.com.util.TrustedNavigationIntents.markTrusted(this, it) }

        val stableId = notificationId?.takeIf { it.isNotBlank() }
            ?.let { ("social:$it").hashCode() } ?: System.currentTimeMillis().toInt()
        var pendingIntent = PendingIntent.getActivity(this, stableId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        var avatarBitmap = getAvatarBitmap(senderAvatar)

        var notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(avatarBitmap ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(readableBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(readableBody))
            .setSubText(accountLabel?.let { "Cuenta: $it" } ?: getString(R.string.app_name))
            .setColor(ContextCompat.getColor(this, R.color.red_primary))
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)

        if (type == "birthday") notificationBuilder.addAction(0, "Felicítalo ahora", pendingIntent)
        if (type == "new_moment" || type == "like_moment") {
            val remaining = runCatching {
                expiresAt?.let { java.time.Instant.parse(it).toEpochMilli() - System.currentTimeMillis() }
            }.getOrNull()
            if (remaining != null && remaining <= 0) return
            if (remaining != null) notificationBuilder.setTimeoutAfter(remaining)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Interacciones Sociales", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Likes, Comentarios y Seguidores"
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
        notificationManager.notify(stableId, notificationBuilder.build())
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun sendChatNotification(
        createdAt: String? = null,
        title: String,
        messageBody: String,
        chatId: String?,
        senderId: String?,
        senderAvatar: String?,
        messageId: String?,
        notificationAccountTarget: NotificationAccountTarget?,
        accountLabel: String?,
        allowInlineActions: Boolean,
        respectMutePreferences: Boolean
    ) {
        var conversationId = chatId?.trim()?.lowercase().orEmpty()
        if (conversationId.isBlank()) return
        if (respectMutePreferences && ConversationNotificationPrefs(this).isMuted(targetType = "chat", conversationId = conversationId, accountId = authService.getCurrentUserId())) {
            android.util.Log.d(TAG, "Chat $conversationId muted via notification prefs. Notification skipped.")
            return
        }

        val avatarBitmap = getMessageSenderBitmap(senderId, senderAvatar, respectMutePreferences)
        if (respectMutePreferences && (
                notificationAccountTarget?.matches(authService.getCurrentUserId(), authService.getCurrentUserEmail()) == false ||
                messageSessionStateStore.isActiveConversationVisible("chat", conversationId) ||
                ConversationNotificationPrefs(this).isMuted("chat", conversationId, authService.getCurrentUserId())
                || messageRepository.isChatMutedLocally(conversationId)
            )) return
        var notificationId = conversationId.hashCode()
        MessageNotificationFactory.post(
            context = this,
            payload = MessageNotificationPayload(
                channelId = getString(R.string.chat_notification_channel_id),
                channelName = "Mensajes Directos",
                channelDescription = "Notificaciones de chats privados",
                conversationId = conversationId,
                targetType = "chat",
                conversationTitle = title,
                senderName = title,
                messageBody = messageBody,
                notificationId = notificationId,
                senderId = senderId,
                messageId = messageId,
                largeIcon = avatarBitmap,
                isGroupConversation = false,
                notificationAccountTarget = notificationAccountTarget?.copy(displayName = accountLabel ?: notificationAccountTarget.displayName),
                allowInlineActions = allowInlineActions,
                createdAt = createdAt
            )
        )
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun sendGroupNotification(
        createdAt: String? = null,
        title: String,
        messageBody: String,
        chatId: String,
        senderId: String?,
        senderName: String,
        groupName: String?,
        conversationAvatar: String?,
        channelContextId: String?,
        senderAvatar: String?,
        isGroup: Boolean,
        isCommunity: Boolean,
        messageId: String?,
        notificationAccountTarget: NotificationAccountTarget?,
        accountLabel: String?,
        respectMutePreferences: Boolean,
        isMention: Boolean = false
    ) {
        var channelId = getString(R.string.group_notification_channel_id)
        var targetType = when {
            isCommunity -> "community"
            isGroup -> "group"
            else -> "chat"
        }
        var conversationId = chatId.trim().lowercase()
        if (respectMutePreferences && !isMention && ConversationNotificationPrefs(this).isMuted(targetType = targetType, conversationId = conversationId, accountId = authService.getCurrentUserId())) {
            android.util.Log.d(TAG, "$targetType $conversationId muted via notification prefs. Notification skipped.")
            return
        }
        val avatarBitmap = getMessageSenderBitmap(senderId, senderAvatar, respectMutePreferences)
        val roomBitmap = getAvatarBitmap(conversationAvatar)
        if (respectMutePreferences && (
                notificationAccountTarget?.matches(authService.getCurrentUserId(), authService.getCurrentUserEmail()) == false ||
                messageSessionStateStore.isActiveConversationVisible(targetType, conversationId) ||
                (!isMention && ConversationNotificationPrefs(this).isMuted(targetType, conversationId, authService.getCurrentUserId()))
            )) return
        MessageNotificationFactory.post(
            context = this,
            payload = MessageNotificationPayload(
                channelId = channelId,
                channelName = "Grupos y Comunidades",
                channelDescription = "Notificaciones de grupos y comunidades",
                conversationId = conversationId,
                targetType = targetType,
                conversationTitle = groupName ?: title,
                senderName = senderName,
                messageBody = messageBody,
                notificationId = conversationId.hashCode(),
                senderId = senderId,
                messageId = messageId,
                largeIcon = avatarBitmap,
                isGroupConversation = true,
                notificationAccountTarget = notificationAccountTarget?.copy(displayName = accountLabel ?: notificationAccountTarget.displayName),
                allowInlineActions = respectMutePreferences,
                conversationIcon = roomBitmap,
                channelContextId = channelContextId,
                createdAt = createdAt
            )
        )
    }

    private fun sendLiveStartedNotification(
        title: String,
        body: String,
        liveId: String,
        hostId: String?,
        hostAvatar: String?,
        notificationAccountTarget: NotificationAccountTarget?,
        accountLabel: String?
    ) {
        val normalizedLiveId = liveId.trim()
        if (normalizedLiveId.isBlank()) {
            sendSystemNotification(title, body)
            return
        }

        val channelId = getString(R.string.live_notification_channel_id)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureLiveNotificationChannel(notificationManager, channelId)

        val openLiveIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            data = Uri.Builder()
                .scheme("novvin")
                .authority("notification")
                .appendPath("live")
                .appendPath(normalizedLiveId)
                .build()
            putExtra("type", "live_started")
            putExtra("live_id", normalizedLiveId)
            putExtra("target_id", normalizedLiveId)
            hostId?.trim()?.takeIf { it.isNotBlank() }?.let { putExtra("host_id", it) }
        }.putNotificationAccountTarget(
            notificationAccountTarget,
            accountLabel
        ).also { conectate.com.util.TrustedNavigationIntents.markTrusted(this, it) }

        val notificationId = ("live_started:$normalizedLiveId").hashCode()
        val contentIntent = PendingIntent.getActivity(
            this,
            notificationId,
            openLiveIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val childNotification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(getAvatarBitmap(hostAvatar) ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(
                accountLabel?.let { "EN VIVO · Cuenta: $it" }
                    ?: "EN VIVO · ${getString(R.string.app_name)}"
            )
            .setColor(ContextCompat.getColor(this, R.color.red_primary))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(LIVE_NOTIFICATION_GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .build()

        val discoveryIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("type", "live_discovery")
        }
        val discoveryPendingIntent = PendingIntent.getActivity(
            this,
            LIVE_NOTIFICATION_SUMMARY_REQUEST_CODE,
            discoveryIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val summaryNotification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.live_notification_summary_title))
            .setContentText(getString(R.string.live_notification_summary_body))
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(getString(R.string.app_name)))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(discoveryPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(LIVE_NOTIFICATION_GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .build()

        // A repeated FCM delivery for the same live updates this exact notification
        // instead of stacking another visual alert on the device.
        notificationManager.notify("$LIVE_NOTIFICATION_TAG:$normalizedLiveId", notificationId, childNotification)
        notificationManager.notify(LIVE_NOTIFICATION_SUMMARY_TAG, LIVE_NOTIFICATION_SUMMARY_ID, summaryNotification)
    }

    private fun ensureLiveNotificationChannel(
        notificationManager: NotificationManager,
        channelId: String
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            channelId,
            getString(R.string.live_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.live_notification_channel_description)
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun sendSystemNotification(title: String, body: String, stableId: String? = null) {
        var channelId = getString(R.string.system_notification_channel_id)
        var intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        var pendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        var notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOnlyAlertOnce(stableId != null)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Sistema", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alertas importantes del sistema"
            }
            notificationManager.createNotificationChannel(channel)
        }
        if (stableId.isNullOrBlank()) notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
        else notificationManager.notify("system:$stableId", stableId.hashCode(), notificationBuilder.build())
    }

    private fun getMessageSenderBitmap(senderId: String?, avatarUrl: String?, resolveProfile: Boolean): Bitmap? {
        if (!resolveProfile || android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return getAvatarBitmap(avatarUrl)
        return runBlocking(Dispatchers.IO) {
            conectate.com.core.media.NotificationAvatarLoader.senderPhoto(this@MyFirebaseMessagingService, notificationUserRepository, senderId, avatarUrl)
        }
    }

    private fun getAvatarBitmap(url: String?): Bitmap? =
        conectate.com.core.media.NotificationAvatarLoader.loadBlocking(this, url)

    private fun sendIncomingCallNotification(invite: conectate.com.data.model.CallInvite, accountTarget: NotificationAccountTarget?) {
        val notificationStarted = android.os.SystemClock.elapsedRealtime()
        var notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        IncomingCallNotificationFactory.ensureChannel(this)
        notificationManager.notify(
            invite.callId.hashCode(),
            IncomingCallNotificationFactory.build(
                context = this,
                invite = invite,
                avatarBitmap = null,
                avatarBytes = null,
                accountTarget = accountTarget,
            )
        )

        val serviceStarted = runCatching {
            // Start the ringing notification immediately. Avatar loading can happen after the UI is already visible.
            IncomingCallForegroundService.start(this, invite, null, accountTarget)
        }.onFailure { error ->
            android.util.Log.e(TAG, "Incoming call foreground service failed: ${error.message}", error)
        }.isSuccess

        serviceScope.launch {
            var avatarBitmap = getAvatarBitmap(invite.callerAvatarUrl)
            var avatarBytes = avatarBitmap?.toNotificationAvatarBytes()
            if (!canRefreshIncomingCallAvatar(
                notificationStillVisible = notificationManager.activeNotifications.any { it.id == invite.callId.hashCode() },
                recentlyCancelled = wasCallRecentlyCancelled(invite.callId),
                hasActiveCall = callRepository.activeCall.value != null,
                elapsedMs = android.os.SystemClock.elapsedRealtime() - notificationStarted,
            )) return@launch
            if (avatarBitmap != null || avatarBytes != null) {
                notificationManager.notify(
                    invite.callId.hashCode(),
                    IncomingCallNotificationFactory.build(
                        context = this@MyFirebaseMessagingService,
                        invite = invite,
                        avatarBitmap = avatarBitmap,
                        avatarBytes = avatarBytes,
                        accountTarget = accountTarget,
                    )
                )
                // Updating the same notification is enough; restarting the service resets its ring timeout.
            }
        }

        val notificationsGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        val fullScreenBlocked =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                !notificationManager.canUseFullScreenIntent()

        if (!notificationsGranted) {
            runCatching {
                startActivity(buildIncomingCallIntent(invite, null).putNotificationAccountTarget(accountTarget))
            }.onFailure { error ->
                android.util.Log.w(TAG, "Incoming call fullscreen fallback failed: ${error.message}")
            }
        } else if (!serviceStarted) {
            android.util.Log.w(TAG, "Incoming call foreground service not started relying on direct call notification")
        } else if (fullScreenBlocked) {
            android.util.Log.w(TAG, "Full screen intent blocked by OS relying on heads-up call notification")
        }
    }

    private fun buildIncomingCallIntent(
        invite: conectate.com.data.model.CallInvite,
        avatarBytes: ByteArray?
    ): Intent {
        return Intent(this, conectate.com.presentation.calls.IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("call_id", invite.callId)
            putExtra("caller_user_id", invite.callerUserId)
            putExtra("caller_name", invite.callerName)
            putExtra("caller_avatar_url", invite.callerAvatarUrl ?: "")
            putExtra("call_title", invite.title)
            putExtra("call_context_type", invite.contextType)
            putExtra("call_context_id", invite.contextId ?: "")
            putExtra("call_is_video", invite.isVideo)
            avatarBytes?.let {
                putExtra(conectate.com.presentation.calls.IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES, it)
            }
        }
    }

    private fun Bitmap.toNotificationAvatarBytes(maxSizeBytes: Int = 96_000): ByteArray? {
        var quality = 90
        while (quality >= 45) {
            val stream = ByteArrayOutputStream()
            compress(Bitmap.CompressFormat.JPEG, quality, stream)
            val bytes = stream.toByteArray()
            if (bytes.size <= maxSizeBytes || quality == 45) {
                return bytes
            }
            quality -= 15
        }
        return null
    }

    private fun markCallRecentlyCancelled(callId: String) {
        callCancellationGuard.mark(callId)
    }

    private fun wasCallRecentlyCancelled(callId: String): Boolean {
        return callCancellationGuard.contains(callId)
    }

    private fun isStaleCallInvite(rawCreatedAt: String?): Boolean {
        val createdAt = rawCreatedAt?.trim().orEmpty()
        if (createdAt.isBlank()) return false
        val createdAtMs = runCatching {
            if (createdAt.all(Char::isDigit)) {
                createdAt.toLong()
            } else {
                java.time.Instant.parse(createdAt).toEpochMilli()
            }
        }.getOrNull() ?: return false
        return System.currentTimeMillis() - createdAtMs > CALL_INVITE_MAX_AGE_MS
    }

    private fun currentNotificationAccountTarget(): NotificationAccountTarget? {
        val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
        val currentEmail = authService.getCurrentUserEmail()?.trim()?.lowercase()
        if (currentUserId.isNullOrBlank() && currentEmail.isNullOrBlank()) {
            return null
        }

        val storedAccount = resolveStoredAccountForTarget(
            NotificationAccountTarget(userId = currentUserId, email = currentEmail),
            accountSessionRepository.accounts.value
        )

        return NotificationAccountTarget(
            userId = currentUserId,
            email = currentEmail,
            displayName = storedAccount?.display_name?.trim()?.ifBlank { null }
        )
    }

    companion object {
        private const val TAG = "MyFirebaseMsgService"
        private const val CALL_INVITE_MAX_AGE_MS = 60_000L
        private const val LIVE_NOTIFICATION_GROUP = "novvin_live_notifications"
        private const val LIVE_NOTIFICATION_TAG = "live_started"
        private const val LIVE_NOTIFICATION_SUMMARY_TAG = "live_started_summary"
        private const val LIVE_NOTIFICATION_SUMMARY_ID = 9_401
        private const val LIVE_NOTIFICATION_SUMMARY_REQUEST_CODE = 9_402
    }
}





