package conectate.com.presentation.messages

/** Main-thread source ownership: an obsolete screen must not delete files a newer capture is reading. */
internal class IncomingShareCleanupQueue<T>(private val payloadId: (T) -> String) {
    internal class Capture internal constructor()
    private val captures = mutableSetOf<Capture>()
    private val deferred = linkedMapOf<String, T>()

    fun beginCapture(): Capture = Capture().also { captures += it }

    fun finishCapture(capture: Capture, activePayloadId: String?): List<T> {
        if (!captures.remove(capture)) return emptyList()
        return drain(activePayloadId)
    }

    fun enqueue(payload: T, activePayloadId: String?): List<T> {
        deferred[payloadId(payload)] = payload
        return drain(activePayloadId)
    }

    private fun drain(activePayloadId: String?): List<T> {
        if (captures.isNotEmpty()) return emptyList()
        val ready = deferred.values.filter { payloadId(it) != activePayloadId }
        ready.forEach { deferred.remove(payloadId(it)) }
        return ready
    }
}
