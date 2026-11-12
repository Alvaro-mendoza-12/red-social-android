package conectate.com.presentation.messages

import conectate.com.data.model.ChatWithLastMessage

/**
 * Single source of truth for the inbox list and its badges.
 *
 * Keeping this shared avoids the classic production bug where the visible list
 * says "0 unread", but the bottom navigation still shows a stale badge because
 * it counted raw/duplicated/hidden rows.
 */
internal fun normalizedVisibleConversations(
    list: List<ChatWithLastMessage>,
    hiddenChatIds: Set<String> = emptySet(),
    hiddenSince: Map<String, String> = emptyMap(),
): List<ChatWithLastMessage> {
    val validChats = list.filter {
        val cleanId = it.chat.id.trim().lowercase()
        val cutoff = hiddenSince[cleanId]
        val isVisible = cleanId !in hiddenChatIds && (cutoff == null ||
            conectate.com.data.repository.message.isAfterInboxClear(it.chat.last_message_at ?: it.lastMessage?.created_at, cutoff))
        val hasContent = !it.chat.last_message.isNullOrBlank() || !it.lastMessage?.content.isNullOrBlank()
        val hasTimestamp = !it.chat.last_message_at.isNullOrBlank() || !it.lastMessage?.created_at.isNullOrBlank()
        isVisible && (hasContent || hasTimestamp)
    }

    val (groups, dms) = validChats.partition { it.chat.is_group }
    val uniqueDms = dms.groupBy { it.otherUserId?.trim()?.lowercase().orEmpty().ifBlank { it.chat.id.trim().lowercase() } }
        .mapNotNull { (_, duplicates) ->
            duplicates.maxWithOrNull(
                compareBy<ChatWithLastMessage> { it.chat.is_pinned }
                    .thenBy { it.chat.safeLastMessageAt }
            )
        }
    val uniqueGroups = groups.groupBy { it.chat.id.trim().lowercase() }
        .mapNotNull { (_, duplicates) ->
            duplicates.maxWithOrNull(
                compareBy<ChatWithLastMessage> { it.chat.is_pinned }
                    .thenBy { it.chat.safeLastMessageAt }
            )
        }

    return (uniqueDms + uniqueGroups).sortedWith(
        compareByDescending<ChatWithLastMessage> { it.chat.is_pinned }
            .thenByDescending { it.chat.safeLastMessageAt }
    )
}

