package conectate.com.data.repository.message

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Coalesce repeated history/realtime snapshots before they can fill the download queue. */
internal class ChatMediaPrefetchGate(
    private val scope: CoroutineScope,
    parallelism: Int = 2,
    private val capacity: Int = 48,
) {
    data class Key(val accountId: String, val sessionEpoch: Long, val source: String)

    private val permits = Semaphore(parallelism)
    private val pending = mutableSetOf<Key>()

    fun enqueue(key: Key, isAllowed: () -> Boolean, work: suspend () -> Unit): Boolean {
        synchronized(pending) {
            if (pending.size >= capacity || !pending.add(key)) return false
        }
        val job = scope.launch {
            permits.withPermit {
                // A deletion, logout or preference change can happen while waiting for a slot.
                if (isAllowed()) work()
            }
        }
        // Also releases a reservation if the scope was cancelled before launch could run.
        job.invokeOnCompletion { synchronized(pending) { pending.remove(key) } }
        return true
    }
}
