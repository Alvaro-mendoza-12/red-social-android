package conectate.com.data.repository

import conectate.com.data.model.LiveGiftEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Session history is independent of animation duration. Replayed socket ACKs are ignored. */
internal class LiveGiftEventStore(private val capacity: Int = 256) {
    private val seen = LinkedHashSet<String>()
    private val mutableEvents = MutableStateFlow<List<LiveGiftEvent>>(emptyList())
    val events: StateFlow<List<LiveGiftEvent>> = mutableEvents.asStateFlow()

    @Synchronized
    fun record(event: LiveGiftEvent, activeLiveId: String): Boolean {
        if (activeLiveId.isBlank() || event.liveStreamId != activeLiveId ||
            event.transactionId.isBlank() || event.transactionId in seen) return false
        seen.add(event.transactionId)
        while (seen.size > 2_048) seen.remove(seen.first())
        mutableEvents.value = (mutableEvents.value + event).takeLast(capacity)
        return true
    }

    @Synchronized
    fun clear() {
        seen.clear()
        mutableEvents.value = emptyList()
    }
}
