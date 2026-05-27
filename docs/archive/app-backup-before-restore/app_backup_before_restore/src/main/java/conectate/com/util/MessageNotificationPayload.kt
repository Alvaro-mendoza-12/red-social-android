package conectate.com.util

import android.graphics.Bitmap

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
    val allowInlineActions: Boolean = false
)
