package conectate.com.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import conectate.com.MainActivity
import conectate.com.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalFoundationApi::class)
class LocalNotificationHelper(private val context: Context) {

    fun showSocialNotification(
        title: String,
        body: String,
        type: String,
        targetId: String,
        senderId: String? = null,
        senderAvatar: String? = null,
        targetType: String? = null,
        extraData: JsonObject? = null
    ) {
        val normalizedType = type.trim().lowercase()
        val normalizedTargetType = targetType?.trim()?.lowercase()?.ifBlank { null }
            ?: getExtraDataValue(extraData, "target_type")?.lowercase()
        val commentId = getExtraDataValue(extraData, "comment_id")
            ?: getExtraDataValue(extraData, "focus_comment_id")
        val messageId = getExtraDataValue(extraData, "message_id")
            ?: getExtraDataValue(extraData, "focus_message_id")
        val groupId = getExtraDataValue(extraData, "group_id")
        val communityId = getExtraDataValue(extraData, "community_id")
        val explicitPostId = getExtraDataValue(extraData, "post_id")
        val explicitVideoId = getExtraDataValue(extraData, "video_id")

        val postLikeTypes = setOf("like", "like_post", "comment", "comment_post", "reply_post", "mention", "mention_post", "share", "share_post")
        val videoLikeTypes = setOf("like_video", "comment_video", "reply_video", "mention_video", "share_video", "admin_warning_video")

        val resolvedPostId = explicitPostId ?: when {
            normalizedTargetType == "post" -> targetId
            normalizedType in postLikeTypes -> targetId
            else -> null
        }
        val resolvedVideoId = explicitVideoId ?: when {
            normalizedTargetType == "video" -> targetId
            normalizedType in videoLikeTypes -> targetId
            else -> null
        }

        val socialTypes = listOf(
            "like", "like_post", "like_video", "follow", "friend_request",
            "comment", "comment_post", "reply_post", "comment_video", "reply_video",
            "mention", "mention_post", "mention_video",
            "share", "share_post", "share_video", "admin_warning_video",
            "challenge_invite", "voice_invite", "voice_access_request"
        )
        
        val channelId = if (normalizedType in socialTypes) {
            context.getString(R.string.social_notification_channel_id)
        } else {
            context.getString(R.string.system_notification_channel_id)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelName = if (normalizedType in socialTypes) "Interacciones Sociales" else "Sistema"
        val channelDesc = if (normalizedType in socialTypes) "Likes, Comentarios y Seguidores" else "Alertas del sistema"

        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = channelDesc
            enableVibration(true)
            enableLights(true)
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", normalizedType)
            senderAvatar?.takeIf { it.isNotBlank() }?.let { putExtra("sender_avatar", it) }
            commentId?.let { putExtra("comment_id", it) }
            messageId?.let { putExtra("message_id", it) }
            groupId?.let { putExtra("group_id", it) }
            communityId?.let { putExtra("community_id", it) }
            normalizedTargetType?.let { putExtra("target_type", it) }

            when (normalizedType) {
                "follow", "friend_request", "voice_access_request" -> {
                    putExtra("follower_id", senderId ?: targetId)
                }
                "like", "like_post", "comment", "comment_post", "reply_post", "mention", "mention_post", "share", "share_post" -> {
                    (resolvedPostId ?: targetId).takeIf { it.isNotBlank() }?.let { putExtra("post_id", it) }
                }
                "like_video", "comment_video", "reply_video", "mention_video", "share_video", "admin_warning_video" -> {
                    (resolvedVideoId ?: targetId).takeIf { it.isNotBlank() }?.let { putExtra("video_id", it) }
                }
                "voice_invite" -> {
                    putExtra("room_id", targetId)
                }
                "challenge_invite" -> {
                    putExtra("challenge_id", targetId)
                }
                "new_message" -> {
                    putExtra("chat_id", targetId)
                    putExtra("target_type", normalizedTargetType ?: "chat")
                }
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(ContextCompat.getColor(context, R.color.red_primary))
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (normalizedType in socialTypes) NotificationCompat.CATEGORY_SOCIAL else NotificationCompat.CATEGORY_SYSTEM)

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }

    fun clearChatNotifications(chatId: String?) {
        if (chatId == null) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(chatId.hashCode())
    }

    private fun getExtraDataValue(extraData: JsonObject?, key: String): String? {
        return extraData
            ?.get(key)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.ifBlank { null }
    }
}
