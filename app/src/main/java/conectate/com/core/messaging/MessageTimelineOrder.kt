package conectate.com.core.messaging

import conectate.com.data.model.Message

/** Pending items stay together, ordered by creation time rather than random UUIDs. */
internal fun orderMessageTimeline(messages: List<Message>): List<Message> = messages.sortedWith(
    compareBy<Message> { if (it.status == "sending") 1 else 0 }
        .thenBy { it.timestampMillis }
        .thenBy { it.id }
)
