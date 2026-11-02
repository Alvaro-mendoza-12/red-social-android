package conectate.com.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import conectate.com.MainActivity
import conectate.com.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

@OptIn(ExperimentalFoundationApi::class)
class LocalNotificationHelper(private val context: Context) {

    fun showSocialNotification(
        account: NotificationAccountTarget,
        title: String,
        body: String,
        type: String,
        targetId: String,
        senderId: String? = null,
        senderAvatar: String? = null,
        targetType: String? = null,
        extraData: JsonObject? = null,
        notificationId: String? = null
    ): AccountNotificationPublication? {
        val owner = account.userId?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return null
        val accountTarget = account.copy(userId = owner)
        if (!conectate.com.data.local.prefs.AppSettingsPreferences.activityNotificationAllowed(context, type)) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        if (isRetiredMomentViewNotification(
                type, getExtraDataValue(extraData, "notification_type"),
                getExtraDataValue(extraData, "notificationType")
            )) return null
        val normalizedType = ActivityNotificationPolicy.normalizedType(type)
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
        val explicitLiveId = getExtraDataValue(extraData, "live_id")
        val resolvedLiveId = explicitLiveId ?: when {
            normalizedType == "live_started" && targetId.isNotBlank() -> targetId.trim()
            else -> null
        }
        val isLiveStarted = normalizedType == "live_started" && !resolvedLiveId.isNullOrBlank()
        val recordId = notificationId?.trim()?.takeIf(String::isNotBlank)
        val publicationKey = if (isLiveStarted) "live:$resolvedLiveId" else recordId ?: UUID.randomUUID().toString()
        val tag = "$SOCIAL_NOTIFICATION_TAG:$owner:$publicationKey"

        val postLikeTypes = setOf("like", "like_post", "comment", "comment_post", "reply_post", "mention", "mention_post", "share", "share_post", "new_post")
        val videoLikeTypes = setOf("like_video", "comment_video", "reply_video", "mention_video", "share_video", "admin_warning_video", "mention_video_description", "new_video")

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

        val socialTypes = ActivityNotificationPolicy.socialTypes
        
        val channelId = when {
            isLiveStarted -> context.getString(R.string.live_notification_channel_id)
            normalizedType in socialTypes -> context.getString(R.string.social_notification_channel_id)
            else -> context.getString(R.string.system_notification_channel_id)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channelName = when {
            isLiveStarted -> context.getString(R.string.live_notification_channel_name)
            normalizedType in socialTypes -> "Interacciones Sociales"
            else -> "Sistema"
        }
        val channelDesc = when {
            isLiveStarted -> context.getString(R.string.live_notification_channel_description)
            normalizedType in socialTypes -> "Likes, Comentarios y Seguidores"
            else -> "Alertas del sistema"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                if (isLiveStarted) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = channelDesc
                enableVibration(true)
                enableLights(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = "${context.packageName}.OPEN_SOCIAL:$owner:$publicationKey"
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putNotificationAccountTarget(accountTarget)
            putExtra("type", normalizedType)
            targetId.trim().takeIf(String::isNotBlank)?.let { putExtra("target_id", it) }
            notificationId?.takeIf { it.isNotBlank() }?.let { putExtra("notification_id", it) }
            putExtra("title", title)
            putExtra("body", body)
            senderAvatar?.takeIf { it.isNotBlank() }?.let { putExtra("sender_avatar", it) }
            commentId?.let { putExtra("comment_id", it) }
            messageId?.let { putExtra("message_id", it) }
            groupId?.let { putExtra("group_id", it) }
            communityId?.let { putExtra("community_id", it) }
            normalizedTargetType?.let { putExtra("target_type", it) }

            when (normalizedType) {
                "birthday" -> { putExtra("birthday_user_id", getExtraDataValue(extraData, "birthday_user_id") ?: senderId ?: targetId); putExtra("target_id", targetId) }
                "follow", "friend_request", "friend_request_accepted", "voice_access_request" -> {
                    putExtra("follower_id", senderId ?: targetId)
                }
                "like", "like_post", "comment", "comment_post", "reply_post", "mention", "mention_post", "share", "share_post", "new_post" -> {
                    (resolvedPostId ?: targetId).takeIf { it.isNotBlank() }?.let { putExtra("post_id", it) }
                }
                "like_video", "comment_video", "reply_video", "mention_video", "share_video", "admin_warning_video", "mention_video_description", "new_video" -> {
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
                "live_started" -> {
                    resolvedLiveId?.let {
                        putExtra("live_id", it)
                        putExtra("target_id", it)
                    }
                    senderId?.trim()?.takeIf { it.isNotBlank() }?.let { putExtra("host_id", it) }
                }
                "new_moment", "like_moment" -> (getExtraDataValue(extraData, "moment_id") ?: targetId)
                    .takeIf(String::isNotBlank)?.let { putExtra("moment_id", it) }
            }
            if (normalizedType == "mention" && !resolvedVideoId.isNullOrBlank()) {
                putExtra("video_id", resolvedVideoId)
                putExtra("target_type", "video")
            }
        }.also { TrustedNavigationIntents.markTrusted(context, it) }

        val pendingIntent = PendingIntent.getActivity(
            context,
            tag.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(accountTarget.presentableLabel()?.let { "Cuenta: $it" } ?: context.getString(R.string.app_name))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(ContextCompat.getColor(context, R.color.red_primary))
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addExtras(Bundle().apply {
                putBoolean(EXTRA_SOCIAL, true)
                putString(EXTRA_RECORD_ID, recordId)
            })
            .setPriority(if (isLiveStarted) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isLiveStarted || normalizedType in socialTypes) NotificationCompat.CATEGORY_SOCIAL else NotificationCompat.CATEGORY_SYSTEM)

        if (normalizedType == "birthday") notificationBuilder.addAction(0, "Felicítalo ahora", pendingIntent)
        if (normalizedType == "new_moment" || normalizedType == "like_moment") {
            val expiry = runCatching {
                getExtraDataValue(extraData, "expires_at")
                    ?.let { java.time.Instant.parse(it).toEpochMilli() }
            }.getOrNull()
            val remaining = expiry?.minus(System.currentTimeMillis())
            if (remaining != null && remaining <= 0) return null
            if (remaining != null) notificationBuilder.setTimeoutAfter(remaining).addExtras(Bundle().apply {
                putLong(MessageNotificationFactory.EXTRA_EXPIRES_AT, expiry!!)
            })
        }

        if (isLiveStarted) {
            notificationBuilder
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setGroup("$LIVE_NOTIFICATION_GROUP:$owner")
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        }

        return if (isLiveStarted) {
            val child = MessageNotificationFactory.publishAccountNotification(
                context, owner, tag,
                tag.hashCode(),
                notificationBuilder.build()
            )
            MessageNotificationFactory.publishAccountNotification(
                context, owner, "$LIVE_NOTIFICATION_SUMMARY_TAG:$owner",
                LIVE_NOTIFICATION_SUMMARY_ID,
                buildLiveSummaryNotification(channelId, accountTarget)
            )
            child
        } else {
            MessageNotificationFactory.publishAccountNotification(
                context, owner, tag,
                tag.hashCode(),
                notificationBuilder.build()
            )
        }
    }

    fun clearSocialNotification(accountId: String?, notificationId: String) {
        if (notificationId.isBlank()) return
        MessageNotificationFactory.cancelAccountNotifications(context, accountId) {
            it.extras.getBoolean(EXTRA_SOCIAL) && it.extras.getString(EXTRA_RECORD_ID) == notificationId
        }
    }

    fun clearAllSocialNotifications(accountId: String?) {
        MessageNotificationFactory.cancelAccountNotifications(context, accountId) { it.extras.getBoolean(EXTRA_SOCIAL) }
    }

    fun clearChatNotifications(chatId: String?) {
        if (chatId == null) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(chatId.hashCode())
    }

    private fun getExtraDataValue(extraData: JsonObject?, key: String): String? {
        return (extraData?.get(key) as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.ifBlank { null }
    }

    private fun buildLiveSummaryNotification(channelId: String, account: NotificationAccountTarget) = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.live_notification_summary_title))
        .setContentText(context.getString(R.string.live_notification_summary_body))
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                LIVE_NOTIFICATION_SUMMARY_REQUEST_CODE,
                Intent(context, MainActivity::class.java).apply {
                    action = "${context.packageName}.OPEN_SOCIAL_SUMMARY:${account.userId}"
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("type", "live_discovery")
                    putNotificationAccountTarget(account)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .setCategory(NotificationCompat.CATEGORY_SOCIAL)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .addExtras(Bundle().apply { putBoolean(EXTRA_SOCIAL, true) })
        .setGroup("$LIVE_NOTIFICATION_GROUP:${account.userId}")
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
        .build()

    private companion object {
        const val SOCIAL_NOTIFICATION_TAG = "novvin_social"
        const val LIVE_NOTIFICATION_GROUP = "novvin_live_notifications"
        const val EXTRA_SOCIAL = "novvin_social_notification"
        const val EXTRA_RECORD_ID = "novvin_social_notification_id"
        const val LIVE_NOTIFICATION_SUMMARY_TAG = "live_started_summary"
        const val LIVE_NOTIFICATION_SUMMARY_ID = 9_401
        const val LIVE_NOTIFICATION_SUMMARY_REQUEST_CODE = 9_402
    }
}
