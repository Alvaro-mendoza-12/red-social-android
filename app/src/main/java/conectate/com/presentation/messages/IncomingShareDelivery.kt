package conectate.com.presentation.messages

/** Whether closing a share may also replace the current navigation destination. */
enum class ShareDismissal(val consumed: Boolean, val mayNavigate: Boolean) {
    IGNORED(false, false),
    CURRENT(true, true),
    SUPERSEDED(true, false),
}

internal class IncomingShareDelivery<T> internal constructor(val generation: Long, val payload: T, val requestId: String)
internal data class ConsumedIncomingShare<T>(val delivery: IncomingShareDelivery<T>, val dismissal: ShareDismissal)

/** Main-thread ownership of intent deliveries; equal payload IDs still represent different arrivals. */
internal class IncomingShareDeliveryGate<T> {
    private var generation = 0L
    private var current: IncomingShareDelivery<T>? = null

    fun beginIntent(): Long = ++generation
    fun isCurrentCapture(capture: Long) = capture == generation

    fun publish(capture: Long, payload: T, requestId: String = java.util.UUID.randomUUID().toString()): IncomingShareDelivery<T>? {
        if (!isCurrentCapture(capture)) return null
        require(requestId.isNotBlank())
        return IncomingShareDelivery(capture, payload, requestId).also { current = it }
    }

    fun consume(expected: IncomingShareDelivery<T>?): ConsumedIncomingShare<T>? {
        if (expected == null || current !== expected) return null
        current = null
        val latest = expected.generation == generation
        if (latest) generation++
        return ConsumedIncomingShare(expected, if (latest) ShareDismissal.CURRENT else ShareDismissal.SUPERSEDED)
    }
}

/** Only this task's saved, matching capture restores an existing user action. */
internal fun restoredShareRequestId(payloadId: String, savedPayloadId: String?, savedRequestId: String?): String? =
    savedRequestId?.takeIf { it.isNotBlank() && savedPayloadId == payloadId }
