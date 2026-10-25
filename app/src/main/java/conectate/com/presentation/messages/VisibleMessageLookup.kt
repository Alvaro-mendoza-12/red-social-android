package conectate.com.presentation.messages

/** Resolves visible rows in O(viewport size), reusing the index until Room emits a new list. */
internal class VisibleMessageLookup<T>(private val messageId: (T) -> String) {
    private var indexedMessages: List<T>? = null
    private var messagesById: Map<String, T> = emptyMap()

    fun find(messages: List<T>, ids: Set<String>): List<T> {
        if (ids.isEmpty()) return emptyList()
        if (indexedMessages !== messages) {
            indexedMessages = messages
            messagesById = messages.associateBy(messageId)
        }
        return ids.mapNotNull(messagesById::get)
    }
}
