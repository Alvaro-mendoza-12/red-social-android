package conectate.com.presentation.wallet

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PlayPurchaseQueue(
    private val scope: CoroutineScope,
    private val onBusy: (Boolean) -> Unit
) {
    private val verifying = Mutex()
    private val pending = mutableSetOf<Pair<String, String>>()

    fun submit(account: String, token: String, verify: suspend () -> Unit) {
        val key = account to token
        synchronized(pending) {
            if (!pending.add(key)) return
            if (pending.size == 1) onBusy(true)
        }
        scope.launch { verifying.withLock { verify() } }.invokeOnCompletion {
            synchronized(pending) {
                pending.remove(key)
                if (pending.isEmpty()) onBusy(false)
            }
        }
    }
}
