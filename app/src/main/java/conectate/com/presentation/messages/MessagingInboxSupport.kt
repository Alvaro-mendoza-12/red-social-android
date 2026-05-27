package conectate.com.presentation.messages

import conectate.com.data.model.ChatFolder
import conectate.com.data.model.ChatWithLastMessage

enum class DirectInboxFocusFilter {
    ALL,
    UNREAD,
    PINNED
}

data class DirectInboxFocusCounts(
    val all: Int = 0,
    val unread: Int = 0,
    val pinned: Int = 0
)

fun buildDistinctConversationList(
    conversations: List<ChatWithLastMessage>
): List<ChatWithLastMessage> {
    val directByUser = linkedMapOf<String, ChatWithLastMessage>()
    val groups = mutableListOf<ChatWithLastMessage>()

    conversations.forEach { conversation ->
        if (conversation.chat.is_group) {
            groups += conversation
            return@forEach
        }

        val identity = conversation.otherUserId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: conversation.chat.participants
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
            ?: conversation.chat.id

        val previous = directByUser[identity]
        if (previous == null || conversation.sortTimestamp() >= previous.sortTimestamp()) {
            directByUser[identity] = conversation
        }
    }

    return (directByUser.values + groups)
        .sortedByDescending { it.sortTimestamp() }
}

fun filterConversationsByFolder(
    conversations: List<ChatWithLastMessage>,
    selectedFolderId: String?,
    folders: List<ChatFolder>
): List<ChatWithLastMessage> {
    val cleanFolderId = selectedFolderId?.trim()?.takeIf { it.isNotBlank() } ?: return conversations
    val folder = folders.firstOrNull { it.id == cleanFolderId } ?: return conversations
    if (folder.chat_ids.isEmpty()) return emptyList()
    val allowedIds = folder.chat_ids.map { it.trim().lowercase() }.toSet()
    return conversations.filter { it.chat.id.trim().lowercase() in allowedIds }
}

fun calculateDirectInboxFocusCounts(
    conversations: List<ChatWithLastMessage>
): DirectInboxFocusCounts {
    val direct = conversations.filterNot { it.chat.is_group }
    return DirectInboxFocusCounts(
        all = direct.size,
        unread = direct.count { it.unreadCount > 0 },
        pinned = direct.count { it.chat.is_pinned }
    )
}

fun filterDirectInboxByFocus(
    conversations: List<ChatWithLastMessage>,
    filter: DirectInboxFocusFilter
): List<ChatWithLastMessage> {
    if (filter == DirectInboxFocusFilter.ALL) return conversations

    return conversations.filter { conversation ->
        if (conversation.chat.is_group) {
            true
        } else {
            when (filter) {
                DirectInboxFocusFilter.ALL -> true
                DirectInboxFocusFilter.UNREAD -> conversation.unreadCount > 0
                DirectInboxFocusFilter.PINNED -> conversation.chat.is_pinned
            }
        }
    }
}

private fun ChatWithLastMessage.sortTimestamp(): String {
    return chat.last_message_at
        ?: lastMessage?.created_at
        ?: chat.created_at
}
