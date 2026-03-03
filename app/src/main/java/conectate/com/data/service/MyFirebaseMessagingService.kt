package conectate.com.data.service

import kotlin.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
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
import conectate.com.data.repository.NotificationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        android.util.Log.d(TAG, "Refreshed token: $token")
        
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
            android.util.Log.d(TAG, "Notification received (WakeLock Acquired). Data: $data")
            if (data.isEmpty()) return

            // 1. Basic Extraction
            val notificationTitle = remoteMessage.notification?.title
            val notificationBody = remoteMessage.notification?.body
            
            // Parse all known target keys (snake_case + camelCase) to avoid notification misrouting.
            val groupId = data["group_id"] ?: data["groupId"]
            val communityId = data["community_id"] ?: data["communityId"]
            var chatId = data["chat_id"] ?: data["chatId"] ?: data["challenge_id"]
            val type = data["type"] ?: "text"
            val senderId = data["sender_id"] ?: data["senderId"] ?: data["uid"]
            val senderName = data["sender_name"] ?: data["senderName"] ?: data["author_name"] ?: notificationTitle ?: "Conéctate"
            val content = data["content"] ?: data["message"] ?: data["body"] ?: data["text"] ?: data["comment"] ?: notificationBody ?: ""
            val targetId = data["target_id"] ?: data["targetId"] ?: data["post_id"] ?: data["video_id"] ?: data["challenge_id"] ?: ""
            val postId = data["post_id"] ?: data["postId"]
            val videoId = data["video_id"] ?: data["videoId"]
            val messageId = data["message_id"] ?: data["focus_message_id"] ?: data["id"]
            val commentId = data["comment_id"] ?: data["focus_comment_id"]
            
            val isGroup = data["is_group"] == "true" ||
                data["isGroup"] == "true" ||
                !groupId.isNullOrBlank()
            val isCommunity = data["is_community"] == "true" ||
                data["isCommunity"] == "true" ||
                !communityId.isNullOrBlank()

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

            // 2. SOCIAL & VIDEOS (Highest Priority Fix)
            val socialTypes = listOf(
                "like", "like_post", "like_video", "follow", "friend_request",
                "comment", "comment_post", "reply_post", "comment_video", "reply_video",
                "mention", "mention_post", "mention_video", "share", "share_post", "share_video", "admin_warning_video",
                "challenge_invite", "voice_invite", "voice_access_request"
            )

            if (type in socialTypes) {
                android.util.Log.d(TAG, "Routing to SOCIAL: $type")
                sendSocialNotification(
                    title = data["title"] ?: senderName,
                    body = data["body"] ?: content,
                    type = type,
                    targetId = targetId,
                    postId = postId,
                    videoId = videoId,
                    senderId = senderId ?: "",
                    senderAvatar = data["sender_avatar"],
                    commentId = commentId
                )
                return // DONE
            }

            // 3. Inject into Repository for Active Chats (Foreground)
            if (!chatId.isNullOrBlank()) {
                serviceScope.launch {
                    try {
                        val incomingId = data["id"] ?: data["message_id"] ?: "fcm_${System.currentTimeMillis()}"
                        if (isGroup) {
                            runCatching { groupRepository.markMessageAsDelivered(chatId, incomingId) }
                            val msg = conectate.com.data.model.GroupMessage(
                                id = incomingId,
                                group_id = chatId,
                                sender_id = senderId ?: "unknown",
                                content = content,
                                content_type = data["content_type"] ?: "text",
                                media_url = data["media_url"] ?: data["url"],
                                created_at = data["created_at"] ?: java.time.Instant.now().toString(),
                                sender_name = senderName
                            )
                            groupRepository.handleIncomingMessage(msg)
                        } else if (isCommunity) {
                            runCatching { communityRepository.markMessageAsDelivered(chatId, incomingId) }
                            val msg = conectate.com.data.model.CommunityMessage(
                                id = incomingId,
                                community_id = chatId,
                                sender_id = senderId ?: "unknown",
                                content = content,
                                content_type = data["content_type"] ?: "text",
                                media_url = data["media_url"] ?: data["url"],
                                created_at = data["created_at"] ?: java.time.Instant.now().toString(),
                                sender_name = senderName
                            )
                            communityRepository.handleIncomingMessage(msg)
                        } else {
                            runCatching { messageRepository.markMessageAsDelivered(chatId, incomingId) }
                            val msg = conectate.com.data.model.Message(
                                id = incomingId,
                                chat_id = chatId,
                                sender_id = senderId ?: "unknown",
                                content = content,
                                content_type = data["content_type"] ?: "text",
                                media_url = data["media_url"] ?: data["url"],
                                created_at = data["created_at"],
                                status = "delivered"
                            )
                            messageRepository.handleIncomingMessage(msg)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Background Repo Insert Error", e)
                    }
                }
            }

            // 4. CHAT ROUTING
            if (isGroup || isCommunity) {
                val groupName = data["group_name"] ?: data["groupName"] ?: data["community_name"] ?: "Grupo/Comunidad"
                sendGroupNotification(
                    title = groupName,
                    messageBody = content,
                    chatId = chatId ?: "group_default",
                    senderId = senderId,
                    senderName = senderName,
                    groupName = groupName,
                    senderAvatar = data["sender_avatar"],
                    groupId = groupId,
                    communityId = communityId,
                    isGroup = isGroup,
                    isCommunity = isCommunity,
                    messageId = messageId
                )
            } else if (!chatId.isNullOrBlank() || type == "new_message") {
                if (!chatId.isNullOrBlank() && messageRepository.isChatMutedLocally(chatId)) {
                    android.util.Log.d(TAG, "Chat $chatId muted locally. Notification skipped.")
                    return
                }
                sendChatNotification(
                    title = senderName,
                    messageBody = content,
                    chatId = chatId,
                    senderId = senderId,
                    senderAvatar = data["sender_avatar"],
                    messageId = messageId
                )
            } else {
                val title = data["title"] ?: data["notification_title"] ?: senderName
                val body = data["body"] ?: data["message"] ?: content
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
        commentId: String?
    ) {
        val channelId = getString(R.string.social_notification_channel_id)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", type)
            commentId?.let { putExtra("comment_id", it) }
            when (type) {
                "follow", "friend_request", "voice_access_request" -> putExtra("follower_id", senderId.ifBlank { targetId })
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
        }

        val pendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val avatarBitmap = getAvatarBitmap(senderAvatar)

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(avatarBitmap ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(ContextCompat.getColor(this, R.color.red_primary))
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Interacciones Sociales", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Likes, Comentarios y Seguidores"
            enableVibration(true)
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun sendChatNotification(
        title: String,
        messageBody: String,
        chatId: String?,
        senderId: String?,
        senderAvatar: String?,
        messageId: String?
    ) {
        val channelId = getString(R.string.chat_notification_channel_id)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("chat_id", chatId)
            putExtra("sender_id", senderId)
            messageId?.let { putExtra("message_id", it) }
            putExtra("target_type", "chat")
            putExtra("type", "new_message")
        }
        val pendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val avatarBitmap = getAvatarBitmap(senderAvatar)
        
        val sender = Person.Builder().setName(title).setKey(senderId).build()
        val messagingStyle = NotificationCompat.MessagingStyle(sender).addMessage(messageBody, System.currentTimeMillis(), sender)

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(avatarBitmap ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setContentText(messageBody)
            .setStyle(messagingStyle)
            .setColor(ContextCompat.getColor(this, R.color.red_primary))
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Mensajes Directos", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Notificaciones de chats privados"
            enableVibration(true)
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
        notificationManager.notify(chatId?.hashCode() ?: System.currentTimeMillis().toInt(), notificationBuilder.build())
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
        groupId: String?,
        communityId: String?,
        isGroup: Boolean,
        isCommunity: Boolean,
        messageId: String?
    ) {
        val channelId = getString(R.string.group_notification_channel_id)
        val targetType = when {
            isCommunity -> "community"
            isGroup -> "group"
            else -> "chat"
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("chat_id", chatId)
            putExtra("sender_id", senderId)
            messageId?.let { putExtra("message_id", it) }
            putExtra("is_group", isGroup)
            putExtra("is_community", isCommunity)
            putExtra("group_id", groupId ?: if (isGroup) chatId else "")
            putExtra("community_id", communityId ?: if (isCommunity) chatId else "")
            putExtra("target_type", targetType)
            putExtra("type", "new_message")
        }
        val pendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val avatarBitmap = getAvatarBitmap(senderAvatar)
        
        val sender = Person.Builder().setName(senderName).setKey(senderId).build()
        val messagingStyle = NotificationCompat.MessagingStyle(sender)
            .addMessage(messageBody, System.currentTimeMillis(), sender)
            .setConversationTitle(groupName ?: "Grupo")
            .setGroupConversation(true)

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(avatarBitmap ?: BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(groupName ?: title)
            .setContentText("$senderName: $messageBody")
            .setStyle(messagingStyle)
            .setColor(ContextCompat.getColor(this, R.color.red_primary))
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Grupos y Comunidades", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Notificaciones de grupos y comunidades"
            enableVibration(true)
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
        notificationManager.notify(chatId.hashCode(), notificationBuilder.build())
    }

    @OptIn(ExperimentalFoundationApi::class)
    private fun sendSystemNotification(title: String, body: String) {
        val channelId = getString(R.string.system_notification_channel_id)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Sistema", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alertas importantes del sistema"
        }
        notificationManager.createNotificationChannel(channel)
        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }

    private fun getAvatarBitmap(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val connection = java.net.URL(url).openConnection()
            connection.doInput = true
            connection.connect()
            val input = connection.inputStream
            BitmapFactory.decodeStream(input)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Avatar download failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "MyFirebaseMsgService"
    }
}
