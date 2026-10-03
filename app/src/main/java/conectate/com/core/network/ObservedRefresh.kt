package conectate.com.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive

/** Discovery refreshes only while a screen observes it; session/signaling work is separate. */
internal suspend fun refreshWhileObserved(
    observers: StateFlow<Int>,
    intervalMs: Long,
    refresh: suspend () -> Unit,
) {
    require(intervalMs > 0)
    observers.map { it > 0 }.distinctUntilChanged().collectLatest { observed ->
        if (!observed) return@collectLatest
        while (currentCoroutineContext().isActive) {
            try { refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep discovery retryable after a transient failure. */ }
            delay(intervalMs)
        }
    }
}
