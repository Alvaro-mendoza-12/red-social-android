package conectate.com.presentation.messages

import conectate.com.data.model.ChatFolder
import conectate.com.data.model.ChatWithLastMessage
import conectate.com.data.model.Message

sealed interface ConversationRenderableItem {
    data class Single(val message: Message) : ConversationRenderableItem
    data class MediaCluster(val items: List<Message>) : ConversationRenderableItem
}

enum class DirectInboxFocusFilter {
    ALL,
    UNREAD,
    PINNED
}

data class DirectInboxFocusCounts(
    val all: Int,
    val unread: Int,
    val pinned: Int
)

fun buildDistinctConversationList(conversations: List<ChatWithLastMessage>): List<ChatWithLastMessage> {
    return conectate.com.util.buildDistinctConversationList(conversations)
}

fun filterConversationsByFolder(
    conversations: List<ChatWithLastMessage>,
    selectedFolderId: String?,
    folders: List<ChatFolder>
): List<ChatWithLastMessage> {
    val folderId = selectedFolderId?.trim().orEmpty()
    if (folderId.isBlank()) return conversations
    val folder = folders.firstOrNull { it.id == folderId } ?: return conversations
    val allowedIds = folder.chat_ids.toSet()
    return conversations.filter { allowedIds.contains(it.chat.id) }
}

fun calculateDirectInboxFocusCounts(conversations: List<ChatWithLastMessage>): DirectInboxFocusCounts {
    val directConversations = conversations.filterNot { it.chat.is_group }
    return DirectInboxFocusCounts(
        all = directConversations.size,
        unread = directConversations.count { it.unreadCount > 0 },
        pinned = directConversations.count { it.chat.is_pinned }
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

fun buildConversationRenderableItems(
    messages: List<Message>,
    idProvider: (Message) -> Any?
): List<ConversationRenderableItem> {
    if (messages.isEmpty()) return emptyList()
    val renderItems = mutableListOf<ConversationRenderableItem>()
    val cluster = mutableListOf<Message>()

    fun flushCluster() {
        if (cluster.isEmpty()) return
        if (cluster.size == 1) {
            renderItems += ConversationRenderableItem.Single(cluster.first())
        } else {
            renderItems += ConversationRenderableItem.MediaCluster(cluster.toList())
        }
        cluster.clear()
    }

    fun isClusterable(message: Message): Boolean {
        idProvider(message)
        return message.content_type == "image" &&
            !message.media_url.isNullOrBlank() &&
            message.content.isBlank()
    }

    messages.forEach { message ->
        if (!isClusterable(message)) {
            flushCluster()
            renderItems += ConversationRenderableItem.Single(message)
            return@forEach
        }

        val previous = cluster.lastOrNull()
        val shouldJoinCluster = previous != null &&
            previous.sender_id == message.sender_id &&
            previous.content_type == message.content_type

        if (!shouldJoinCluster) {
            flushCluster()
        }

        cluster += message
    }

    flushCluster()
    return renderItems
}
