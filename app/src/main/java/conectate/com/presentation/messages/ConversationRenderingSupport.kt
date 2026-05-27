package conectate.com.presentation.messages

import conectate.com.data.model.Message

sealed class ConversationRenderableItem<out T> {
    data class Single<T>(val item: T) : ConversationRenderableItem<T>()
    data class MediaCluster<T>(val items: List<T>) : ConversationRenderableItem<T>()
}

fun <T> buildConversationRenderableItems(
    messages: List<Message>,
    mapMessage: (Message) -> T
): List<ConversationRenderableItem<T>> {
    val result = mutableListOf<ConversationRenderableItem<T>>()
    var index = 0

    while (index < messages.size) {
        val current = messages[index]
        if (!current.canJoinMediaCluster()) {
            result += ConversationRenderableItem.Single(mapMessage(current))
            index += 1
            continue
        }

        val cluster = mutableListOf(current)
        var nextIndex = index + 1
        while (nextIndex < messages.size) {
            val next = messages[nextIndex]
            if (!next.canJoinMediaCluster() || next.sender_id != current.sender_id) break
            cluster += next
            nextIndex += 1
        }

        if (cluster.size >= 2) {
            result += ConversationRenderableItem.MediaCluster(cluster.map(mapMessage))
        } else {
            result += ConversationRenderableItem.Single(mapMessage(current))
        }
        index = nextIndex
    }

    return result
}

private fun Message.canJoinMediaCluster(): Boolean {
    if (deleted_for_all) return false
    if (content.isNotBlank()) return false
    if (media_url.isNullOrBlank()) return false
    return content_type in setOf("image", "video", "gif")
}
