package conectate.com.data.service

import kotlin.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
    lateinit var callRepository: CallRepository

    @Inject
    lateinit var authService: AuthService

    @Inject
    lateinit var accountSessionRepository: AccountSessionRepository

    @Inject
    lateinit var multiAccountInboxRepository: MultiAccountInboxRepository

    @Inject
    lateinit var messageSessionStateStore: MessageSessionStateStore

    private val serviceScope = newSafeScope("FirebaseMessagingService", Dispatchers.IO)
    private val callGuardPrefs by lazy {
        applicationContext.getSharedPreferences(CALL_GUARD_PREFS, Context.MODE_PRIVATE)
    }

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
            val displayContent = buildConversationBackgroundPreview(contentType, content) ?: content
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

            if (!isTargetingActiveAccount && !chatId.isNullOrBlank()) {
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

                // BUSY HANDLING: If already in a call, auto-reject this invite (Edge Case: Multiple Calls)
                if (callRepository.activeCall.value != null && !callRepository.activeCall.value?.callId.equals(callId)) {
                    android.util.Log.i(TAG, "User busy in another call. Auto-declining callId=$callId")
                    serviceScope.launch {
                        runCatching {
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
                            callRepository.declineIncomingCall(invite)
                        }
                    }
                    return
                }

                var invite = conectate.com.data.model.CallInvite(
                    callId = callId,
                    title = callTitle,
                    callerUserId = senderId,
                    callerName = senderName,
                    callerAvatarUrl = callerAvatar,
                    contextType = contextType,
                    contextId = contextId,
                    isVideo = isVideoCall
                )

                var isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                android.util.Log.i(
                    TAG,
                    "Received call_invite via FCM: callId=$callId sender=$senderId foreground=$isForeground"
                )
                if (isForeground && isTargetingActiveAccount) {
                    // App open: show in-app incoming UI (avoids duplicate notifications)
                    callRepository.presentIncomingInvite(invite)
                } else {
                    sendIncomingCallNotification(invite)
                }
                return
            }

            if (type.trim().equals("call_cancel", ignoreCase = true)) {
                var callId = (data["call_id"] ?: data["callId"] ?: targetId).trim()
                if (callId.isNotBlank()) {
                    val action = (data["call_action"] ?: data["callAction"] ?: "cancel").trim()
                    callRepository.handleExternalCallResponse(callId, action, senderId.orEmpty())
                    markCallRecentlyCancelled(callId)
                    var notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(callId.hashCode())
                    IncomingCallForegroundService.stop(this)
                    if (isTargetingActiveAccount) {
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

            // 2. SOCIAL & VIDEOS (Highest Priority Fix)
            val socialTypes = listOf(
                "like", "like_post", "like_video", "follow", "friend_request", "friend_request_accepted",
                "comment", "comment_post", "reply_post", "comment_video", "reply_video",
                "mention", "mention_post", "mention_video", "share", "share_post", "share_video", "admin_warning_video",
                "challenge_invite", "voice_invite", "voice_access_request"
            )

            if (type in socialTypes) {
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
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(chatId.hashCode())
                android.util.Log.d(TAG, "Conversation $activeConversationType/$chatId visible; notification skipped.")
                return
            }

            // 4. CHAT ROUTING
            if (isGroup || isCommunity) {
                var groupName = data["group_name"] ?: data["groupName"] ?: data["community_name"] ?: "Grupo/Comunidad"
                sendGroupNotification(
                    title = groupName,
                    messageBody = displayContent,
                    chatId = chatId ?: "group_default",
                    senderId = senderId,
                    senderName = senderName,
                    groupName = groupName,
                    senderAvatar = data["sender_avatar"],
                    isGroup = isGroup,
                    isCommunity = isCommunity,
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
                if (body.isNotBlank()) {
                    sendSystemNotification(title, body)
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
        notificationAccountTarget: NotificationAccountTarget?,
        accountLabel: String?
    ) {
        var channelId = getString(R.string.social_notification_channel_id)
        var intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", type)
            commentId?.let { putExtra("comment_id", it) }
            when (type) {
                "follow", "friend_request", "friend_request_accepted", "voice_access_request" -> putExtra("follower_id", senderId.ifBlank { targetId })
                "like", "like_post", "comment", "comment_post", "reply_post", "mention", "mention_post", "share", "share_post" -> putExtra("post_id", targetId)
                "like_video", "comment_video", "reply_video", "mention_video", "share_video", "admin_warning_video" -> putExtra("video_id", targetId)
                "voice_invite" -> putExtra("room_id", targetId)
                "challenge_invite" -> putExtra("challenge_id", targetId)
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

        var pendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        var avatarBitmap = getAvatarBitmap(senderAvatar)

        var notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(avatarBitmap ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(accountLabel?.let { "Cuenta: $it" } ?: getString(R.string.app_name))
            .setColor(ContextCompat.getColor(this, R.color.red_primary))
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Interacciones Sociales", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Likes, Comentarios y Seguidores"
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun sendChatNotification(
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
        if (respectMutePreferences && ConversationNotificationPrefs(this).isMuted(targetType = "chat", conversationId = conversationId)) {
            android.util.Log.d(TAG, "Chat $conversationId muted via notification prefs. Notification skipped.")
            return
        }

        var avatarBitmap = getAvatarBitmap(senderAvatar)
        var notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var notificationId = conversationId.hashCode()
        var notification = MessageNotificationFactory.build(
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
                allowInlineActions = allowInlineActions
            )
        )
        notificationManager.notify(notificationId, notification)
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun sendGroupNotification(
        title: String,
        messageBody: String,
        chatId: String,
        senderId: String?,
        senderName: String,
        groupName: String?,
        senderAvatar: String?,
        isGroup: Boolean,
        isCommunity: Boolean,
        messageId: String?,
        notificationAccountTarget: NotificationAccountTarget?,
        accountLabel: String?,
        respectMutePreferences: Boolean
    ) {
        var channelId = getString(R.string.group_notification_channel_id)
        var targetType = when {
            isCommunity -> "community"
            isGroup -> "group"
            else -> "chat"
        }
        var conversationId = chatId.trim().lowercase()
        if (respectMutePreferences && ConversationNotificationPrefs(this).isMuted(targetType = targetType, conversationId = conversationId)) {
            android.util.Log.d(TAG, "$targetType $conversationId muted via notification prefs. Notification skipped.")
            return
        }
        var avatarBitmap = getAvatarBitmap(senderAvatar)
        var notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var notification = MessageNotificationFactory.build(
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
                allowInlineActions = false
            )
        )
        notificationManager.notify(conversationId.hashCode(), notification)
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun sendSystemNotification(title: String, body: String) {
        var channelId = getString(R.string.system_notification_channel_id)
        var intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        var pendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        var notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
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
        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }

    private fun getAvatarBitmap(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val connection = java.net.URL(url).openConnection().apply {
                connectTimeout = 1500
                readTimeout = 2000
                useCaches = true
            }
            connection.doInput = true
            connection.connect()
            connection.getInputStream().use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Avatar download failed: ${e.message}")
            null
        }
    }

    private fun sendIncomingCallNotification(invite: conectate.com.data.model.CallInvite) {
        var notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        IncomingCallNotificationFactory.ensureChannel(this)
        notificationManager.notify(
            invite.callId.hashCode(),
            IncomingCallNotificationFactory.build(
                context = this,
                invite = invite,
                avatarBitmap = null,
                avatarBytes = null
            )
        )

        val serviceStarted = runCatching {
            // Start the ringing notification immediately. Avatar loading can happen after the UI is already visible.
            IncomingCallForegroundService.start(this, invite, null)
        }.onFailure { error ->
            android.util.Log.e(TAG, "Incoming call foreground service failed: ${error.message}", error)
        }.isSuccess

        serviceScope.launch {
            var avatarBitmap = getAvatarBitmap(invite.callerAvatarUrl)
            var avatarBytes = avatarBitmap?.toNotificationAvatarBytes()
            if (avatarBitmap != null || avatarBytes != null) {
                notificationManager.notify(
                    invite.callId.hashCode(),
                    IncomingCallNotificationFactory.build(
                        context = this@MyFirebaseMessagingService,
                        invite = invite,
                        avatarBitmap = avatarBitmap,
                        avatarBytes = avatarBytes
                    )
                )
                runCatching {
                    IncomingCallForegroundService.start(this@MyFirebaseMessagingService, invite, avatarBytes)
                }.onFailure { error ->
                    android.util.Log.w(TAG, "Incoming call avatar refresh failed: ${error.message}")
                }
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
                startActivity(buildIncomingCallIntent(invite, null))
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
        var cleanId = callId.trim()
        if (cleanId.isBlank()) return
        var now = System.currentTimeMillis()
        pruneOldCallGuards(now)
        callGuardPrefs.edit()
            .putLong("$CALL_GUARD_CANCELLED_PREFIX$cleanId", now)
            .apply()
    }

    private fun wasCallRecentlyCancelled(callId: String): Boolean {
        var cleanId = callId.trim()
        if (cleanId.isBlank()) return false
        val key = "$CALL_GUARD_CANCELLED_PREFIX$cleanId"
        val cancelledAt = callGuardPrefs.getLong(key, 0L)
        if (cancelledAt <= 0L) return false
        var now = System.currentTimeMillis()
        return if (now - cancelledAt <= CALL_GUARD_CANCEL_WINDOW_MS) {
            true
        } else {
            callGuardPrefs.edit().remove(key).apply()
            false
        }
    }

    private fun pruneOldCallGuards(now: Long = System.currentTimeMillis()) {
        val editor = callGuardPrefs.edit()
        var changed = false
        callGuardPrefs.all.forEach { (key, value) ->
            if (!key.startsWith(CALL_GUARD_CANCELLED_PREFIX)) return@forEach
            val timestamp = value as? Long ?: return@forEach
            if (now - timestamp > CALL_GUARD_CANCEL_WINDOW_MS) {
                editor.remove(key)
                changed = true
            }
        }
        if (changed) editor.apply()
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
        private const val CALL_GUARD_PREFS = "call_notification_guard"
        private const val CALL_GUARD_CANCELLED_PREFIX = "cancelled_call_"
        private const val CALL_GUARD_CANCEL_WINDOW_MS = 120_000L
        private const val CALL_INVITE_MAX_AGE_MS = 60_000L
    }
}





