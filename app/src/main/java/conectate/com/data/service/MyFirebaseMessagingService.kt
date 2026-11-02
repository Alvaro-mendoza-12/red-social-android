package conectate.com.data.service

import kotlin.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import conectate.com.MainActivity
import conectate.com.R
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.repository.AccountSessionRepository
import conectate.com.data.repository.CallRepository
import conectate.com.data.repository.MultiAccountInboxRepository
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
        
        conectate.com.data.sync.PushRegistrationScheduler.enqueue(applicationContext)
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
            val chatId = conectate.com.util.pushConversationId(data)
            val type = data["type"]?.trim()?.lowercase().orEmpty()
            val contentType = data["content_type"]?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: "text"
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
            
            val conversationKind = conectate.com.util.pushConversationKind(data)
            val isCommunity = conversationKind == "community"
            val isGroup = conversationKind == "group"
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
            val receivedLease = authService.captureAccountLease()
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
                if (effectiveAccountTarget == null || !canStillNotify(effectiveAccountTarget)) return
                if (ConversationNotificationPrefs(this).isMuted(scopeType, conversationId, effectiveAccountTarget.userId)) return
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

            val activityType = conectate.com.util.ActivityNotificationPolicy.normalizedType(type)
            if (activityType == "live_started" || activityType in conectate.com.util.ActivityNotificationPolicy.socialTypes) {
                val action = when (activityType) {
                    "friend_request_accepted" -> getString(R.string.notification_friend_accepted)
                    "friend_request" -> getString(R.string.notification_friend_requested)
                    "follow" -> getString(R.string.notification_followed)
                    else -> null
                }
                val liveId = data["live_id"] ?: data["liveId"] ?: targetId
                val readableBody = if (activityType == "live_started")
                    (data["body"] ?: data["message"])?.takeIf(String::isNotBlank) ?: "Está en vivo ahora."
                    else conectate.com.util.socialNotificationBody(data["title"] ?: senderName, data["body"] ?: displayContent, action)
                sendActivityNotification(
                    title = data["title"]?.takeIf(String::isNotBlank) ?: senderName,
                    body = readableBody, type = activityType,
                    targetId = if (activityType == "live_started") liveId else targetId,
                    senderId = if (activityType == "live_started") data["host_id"] ?: data["hostId"] ?: senderId else senderId,
                    senderAvatar = data["sender_avatar"] ?: data["senderAvatar"] ?: data["actor_avatar_url"] ?: data["host_avatar_url"],
                    notificationId = data["notification_id"], notificationAccountTarget = effectiveAccountTarget,
                    accountLabel = targetAccountLabel, data = data,
                )
                return
            }

            // Only actual server message deliveries can enter the local replica or message alerts.
            val isMessageDelivery = conectate.com.util.isMessageDeliveryPush(type, messageId, senderId, effectiveAccountTarget?.userId)
            val deliveryLease = receivedLease
            if (isMessageDelivery && isTargetingActiveAccount && !chatId.isNullOrBlank()) {
                // La notificación no debe adelantarse a la fila local: si el
                // usuario la toca inmediatamente, el mensaje ya debe existir.
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(2_000L) {
                    try {
                        deliveryLease.requireCurrent()
                        val incomingId = requireNotNull(messageId)
                        if (isGroup) {
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
                            groupRepository.handleIncomingMessage(deliveryLease, msg)
                        } else if (isCommunity) {
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
                            communityRepository.handleIncomingMessage(deliveryLease, msg)
                        } else {
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
                            messageRepository.handleIncomingMessage(deliveryLease, msg)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (e: Exception) {
                        android.util.Log.e(TAG, "Background Repo Insert Error", e)
                    }
                    }
                }
            }
            
            if (!isMessageDelivery) {
                val body = data["body"] ?: data["message"] ?: displayContent
                if (body.isNotBlank()) sendActivityNotification(data["title"] ?: senderName, body, type,
                    targetId, senderId, data["sender_avatar"], data["notification_id"], effectiveAccountTarget, targetAccountLabel, data)
                return
            }
            if (effectiveAccountTarget == null || !canStillNotify(effectiveAccountTarget)) return
            if (isTargetingActiveAccount) deliveryLease.requireCurrent()

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
                    respectMutePreferences = isTargetingActiveAccount,
                    activeLease = if (isTargetingActiveAccount) deliveryLease else null
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
                    respectMutePreferences = isTargetingActiveAccount,
                    activeLease = if (isTargetingActiveAccount) deliveryLease else null
                )
            } else {
                val title = data["title"] ?: data["notification_title"] ?: senderName
                val body = data["body"] ?: data["message"] ?: displayContent
                if (body.isNotBlank()) sendActivityNotification(
                    title, body, type, targetId, senderId, data["sender_avatar"],
                    data["notification_id"], effectiveAccountTarget, targetAccountLabel, data,
                )
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            android.util.Log.d(TAG, "Notification processing stopped because its session changed")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Could not process an incoming notification", e)
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
                android.util.Log.d(TAG, "WakeLock Released.")
            }
        }
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
        respectMutePreferences: Boolean,
        activeLease: conectate.com.data.supabase.AuthSessionLease?
    ) {
        val owner = notificationAccountTarget ?: return
        if (!canStillNotify(owner)) return
        activeLease?.requireCurrent()
        var conversationId = chatId?.trim()?.lowercase().orEmpty()
        if (conversationId.isBlank()) return
        if (ConversationNotificationPrefs(this).isMuted(targetType = "chat", conversationId = conversationId, accountId = owner.userId)) {
            android.util.Log.d(TAG, "Chat $conversationId muted via notification prefs. Notification skipped.")
            return
        }

        val avatarBitmap = getMessageSenderBitmap(senderId, senderAvatar, respectMutePreferences)
        activeLease?.requireCurrent()
        if (!canStillNotify(owner) || !appSettingsPreferences.messageNotificationsEnabled.value ||
            ConversationNotificationPrefs(this).isMuted("chat", conversationId, owner.userId)) return
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
        activeLease: conectate.com.data.supabase.AuthSessionLease?,
        isMention: Boolean = false
    ) {
        val owner = notificationAccountTarget ?: return
        if (!canStillNotify(owner)) return
        activeLease?.requireCurrent()
        var channelId = getString(R.string.group_notification_channel_id)
        var targetType = when {
            isCommunity -> "community"
            isGroup -> "group"
            else -> "chat"
        }
        var conversationId = chatId.trim().lowercase()
        if (!isMention && ConversationNotificationPrefs(this).isMuted(targetType = targetType, conversationId = conversationId, accountId = owner.userId)) {
            android.util.Log.d(TAG, "$targetType $conversationId muted via notification prefs. Notification skipped.")
            return
        }
        val avatarBitmap = getMessageSenderBitmap(senderId, senderAvatar, respectMutePreferences)
        val roomBitmap = getAvatarBitmap(conversationAvatar)
        activeLease?.requireCurrent()
        if (!canStillNotify(owner) || !appSettingsPreferences.messageNotificationsEnabled.value ||
            (!isMention && ConversationNotificationPrefs(this).isMuted(targetType, conversationId, owner.userId))) return
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

    /** Show text immediately. Optional photos may only enrich this exact still-visible attempt. */
    private fun sendActivityNotification(
        title: String,
        body: String,
        type: String,
        targetId: String,
        senderId: String?,
        senderAvatar: String?,
        notificationId: String?,
        notificationAccountTarget: NotificationAccountTarget?,
        accountLabel: String?,
        data: Map<String, String>,
    ) {
        val account = notificationAccountTarget ?: return
        if (!canStillNotify(account)) return
        val extra = buildJsonObject { data.forEach { (key, value) -> put(key, value) } }
        val publication = conectate.com.util.LocalNotificationHelper(this).showSocialNotification(
            account = account.copy(displayName = accountLabel ?: account.displayName),
            title = title, body = body, type = type, targetId = targetId,
            senderId = senderId, senderAvatar = senderAvatar, targetType = data["target_type"],
            extraData = extra, notificationId = notificationId,
        ) ?: return
        if (!senderAvatar.isNullOrBlank()) serviceScope.launch {
            val bitmap = conectate.com.core.media.NotificationAvatarLoader.load(this@MyFirebaseMessagingService, senderAvatar)
                ?: return@launch
            if (canStillNotify(account) &&
                AppSettingsPreferences.activityNotificationAllowed(this@MyFirebaseMessagingService, type)) {
                MessageNotificationFactory.updateAccountNotificationAvatar(this@MyFirebaseMessagingService, publication, bitmap)
            }
        }
    }

    private fun canStillNotify(account: NotificationAccountTarget): Boolean =
        conectate.com.util.canReceiveAccountNotification(account, authService.getCurrentUserId(),
            authService.getCurrentUserEmail(), authService.isLoggedIn(), accountSessionRepository.accounts.value)

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
    }
}





