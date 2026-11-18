package conectate.com.presentation.wallet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PlayPurchaseQueue(
    private val scope: CoroutineScope,
    private val onBusy: (Boolean) -> Unit
) {
    private var verifying = Mutex()
    private val pending = mutableMapOf<Pair<String, String>, Job>()

    fun submit(account: String, token: String, verify: suspend () -> Unit) {
        val key = account to token
        val job: Job
        synchronized(pending) {
            if (pending.containsKey(key)) return
            val visitMutex = verifying
            job = scope.launch(start = CoroutineStart.LAZY) { visitMutex.withLock { verify() } }
            pending[key] = job
            if (pending.size == 1) onBusy(true)
        }
        job.invokeOnCompletion {
            synchronized(pending) {
                if (pending[key] === job) {
                    pending.remove(key)
                    if (pending.isEmpty()) onBusy(false)
                }
            }
        }
        job.start()
    }

    fun cancelPending() {
        val previous = synchronized(pending) {
            pending.values.toList().also {
                pending.clear()
                verifying = Mutex()
                onBusy(false)
            }
        }
        previous.forEach { it.cancel() }
    }
}
