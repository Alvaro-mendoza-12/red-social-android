package conectate.com.presentation.wallet

import conectate.com.data.model.LiveGiftEvent

/** One animation at a time; confirmed bursts wait without replacing the current gift. */
internal class GiftEventQueue(private val capacity: Int = 32) {
    private val queued = ArrayDeque<LiveGiftEvent>()
    private val seen = LinkedHashSet<String>()

    fun offerAll(events: List<LiveGiftEvent>) {
        events.forEach { event ->
            if (event.transactionId.isBlank() || event.transactionId in seen) return@forEach
            if (queued.size < capacity) {
                seen.add(event.transactionId)
                queued.addLast(event)
            }
        }
        trimSeen()
    }

    fun peek(): LiveGiftEvent? = queued.firstOrNull()

    fun complete(transactionId: String) {
        if (queued.firstOrNull()?.transactionId == transactionId) queued.removeFirst()
    }

    fun size(): Int = queued.size

    fun clear() { queued.clear() }

    fun discardAll(events: List<LiveGiftEvent>) {
        events.filter { it.transactionId.isNotBlank() }.forEach { seen.add(it.transactionId) }
        clear()
        trimSeen()
    }

    private fun trimSeen() {
        while (seen.size > 2_048) seen.remove(seen.first())
    }
}
