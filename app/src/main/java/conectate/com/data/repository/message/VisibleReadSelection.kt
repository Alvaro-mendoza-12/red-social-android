package conectate.com.data.repository.message

import conectate.com.core.messaging.MessageStatusNormalizer
import conectate.com.data.local.dao.VisibleReadCandidate

// Keep binds below the oldest supported SQLite limit, including the conversation ID.
internal const val VISIBLE_READ_BATCH_SIZE = 200

internal suspend fun selectVisibleIncomingReadIds(
    visibleIds: List<String>,
    conversationId: String,
    accountId: String,
    query: suspend (List<String>) -> List<VisibleReadCandidate>,
): Set<String> = buildSet {
    for (batch in visibleIds.chunked(VISIBLE_READ_BATCH_SIZE)) {
        for (message in query(batch)) {
            // Check the conversation after point lookups so SQLite can use the message primary key.
            if (message.conversationId != conversationId || message.senderId.trim().lowercase() == accountId) continue
            val status = MessageStatusNormalizer.canonical(message.status, message.deliveredAt, message.readAt)
            if (MessageStatusNormalizer.isEligibleForReadTransition(status)) {
                add(message.id)
            }
        }
    }
}
