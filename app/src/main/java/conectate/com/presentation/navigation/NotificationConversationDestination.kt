package conectate.com.presentation.navigation

import android.content.Intent

/** Shared by message and reaction alerts; preserve the exact conversation and focused message. */
@Suppress("DEPRECATION")
internal fun notificationConversationDestination(intent: Intent): String? {
    fun text(key: String) = intent.getStringExtra(key)?.trim()?.takeIf(String::isNotEmpty)
    fun flag(key: String) = when (val value = intent.extras?.get(key)) {
        is Boolean -> value
        is String -> value.trim().equals("true", ignoreCase = true)
        else -> false
    }
    val chatId = text("chat_id") ?: text("target_id")
    val senderId = text("sender_id")
    val messageId = text("message_id") ?: text("focus_message_id")
    val groupId = text("group_id")
    val communityId = text("community_id")
    val kind = when (text("target_type")?.lowercase()) {
        "chat", "dm", "direct" -> "chat"
        "group" -> "group"
        "community" -> "community"
        else -> when {
            communityId != null || flag("is_community") -> "community"
            groupId != null || flag("is_group") -> "group"
            else -> "chat"
        }
    }
    return when (kind) {
        "community" -> (communityId ?: chatId)?.let { Screen.CommunityChat.createRoute(it, messageId) }
        "group" -> (groupId ?: chatId)?.let { Screen.GroupChat.createRoute(it, messageId) }
        else -> (senderId ?: chatId)?.let { Screen.Chat.createRoute(it, focusMessageId = messageId) }
    }
}
