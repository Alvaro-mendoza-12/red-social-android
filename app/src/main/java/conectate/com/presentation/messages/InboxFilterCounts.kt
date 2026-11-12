package conectate.com.presentation.messages

import conectate.com.data.model.ChatFolder
import conectate.com.data.model.ChatWithLastMessage

/** Unread conversations, never message totals or the currently searched subset. */
data class InboxFilterCounts(
    val all: Int = 0,
    val unread: Int = 0,
    val favorites: Int = 0,
    val archived: Int = 0,
    val folders: Map<String, Int> = emptyMap(),
)

internal fun calculateInboxFilterCounts(
    conversations: List<ChatWithLastMessage>,
    folders: List<ChatFolder> = emptyList(),
    hiddenSince: Map<String, String> = emptyMap(),
): InboxFilterCounts {
    val unread = normalizedVisibleConversations(conversations, hiddenSince = hiddenSince)
        .filter { !it.chat.is_group && it.unreadCount > 0 }
    val active = unread.filter { !it.chat.is_archived }
    return InboxFilterCounts(
        all = active.size,
        unread = active.size,
        favorites = active.count { it.chat.is_favorite },
        archived = unread.count { it.chat.is_archived },
        folders = folders.associate { folder ->
            val ids = folder.chat_ids.map { it.trim().lowercase() }.toSet()
            folder.id to active.count { it.chat.id.trim().lowercase() in ids }
        },
    )
}
