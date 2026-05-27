package conectate.com.util

import conectate.com.data.model.ChatWithLastMessage

fun shouldKeepConversationVisible(
    conversation: ChatWithLastMessage
): Boolean {
    val hasContent = !conversation.chat.last_message.isNullOrBlank() ||
        !conversation.lastMessage?.content.isNullOrBlank()
    val hasTimestamp = !conversation.chat.last_message_at.isNullOrBlank() ||
        !conversation.lastMessage?.created_at.isNullOrBlank()
    val hasStableIdentity = if (conversation.chat.is_group) {
        !conversation.chat.group_name.isNullOrBlank() ||
            conversation.chat.participants.isNotEmpty() ||
            conversation.chat.id.isNotBlank()
    } else {
        !conversation.otherUserId.isNullOrBlank() ||
            !conversation.otherUserName.isNullOrBlank() ||
            conversation.chat.participants.size >= 2
    }

    return hasContent || hasTimestamp || hasStableIdentity
}

fun buildDistinctConversationList(
    conversations: List<ChatWithLastMessage>
): List<ChatWithLastMessage> {
    if (conversations.isEmpty()) return emptyList()

    val validChats = conversations.filter(::shouldKeepConversationVisible)
    val (groups, dms) = validChats.partition { it.chat.is_group }

    val uniqueDms = dms.groupBy { it.otherUserId ?: it.chat.id }
        .map { (_, duplicates) ->
            duplicates.maxWithOrNull(
                compareBy<ChatWithLastMessage> { it.chat.is_pinned }
                    .thenBy { it.chat.safeLastMessageAt }
            ) ?: duplicates.first()
        }

    val uniqueGroups = groups.groupBy { it.chat.id }
        .map { (_, duplicates) ->
            duplicates.maxWithOrNull(
                compareBy<ChatWithLastMessage> { it.chat.is_pinned }
                    .thenBy { it.chat.safeLastMessageAt }
            ) ?: duplicates.first()
        }

    return (uniqueDms + uniqueGroups)
        .sortedWith(
            compareByDescending<ChatWithLastMessage> { it.chat.is_pinned }
                .thenByDescending { it.chat.safeLastMessageAt }
        )
}

fun countDistinctUnreadDirectConversations(
    conversations: List<ChatWithLastMessage>
): Int {
    return buildDistinctConversationList(conversations)
        .count { !it.chat.is_group && it.unreadCount > 0 }
}


