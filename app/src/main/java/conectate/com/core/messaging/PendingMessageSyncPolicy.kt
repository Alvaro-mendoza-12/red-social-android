package conectate.com.core.messaging

import conectate.com.data.model.Message

/**
 * Keeps the acknowledgement path for an offline message deterministic.
 *
 * The queued instance is the only authoritative local copy when a backend
 * accepts an idempotent send but does not return a full message body. In
 * particular, it may already contain the remote URL of media that was copied
 * out of the private pending-media directory before the request was made.
 */
internal object PendingMessageSyncPolicy {
    fun acknowledgedMessage(
        serverMessage: Message?,
        queuedMessage: Message,
        chatId: String
    ): Message {
        val acknowledged = serverMessage ?: queuedMessage.copy(status = "sent")
        return acknowledged.copy(
            chat_id = chatId.trim().lowercase(),
            media_url = acknowledged.media_url ?: queuedMessage.media_url,
            thumbnail_url = acknowledged.thumbnail_url ?: queuedMessage.thumbnail_url,
            status = MessageStatusNormalizer.acknowledgedAfterSend(
                status = acknowledged.status,
                deliveredAt = acknowledged.delivered_at,
                readAt = acknowledged.read_at
            )
        )
    }
}
