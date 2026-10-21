package conectate.com.core.messaging

import conectate.com.data.model.Message

/** Delivery/upload changes must not reorder messages with the same creation time. */
internal fun orderMessageTimeline(messages: List<Message>): List<Message> = messages.sortedWith(
    compareBy<Message> { it.timestampMillis }
        .thenBy { it.id }
)
