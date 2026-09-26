package conectate.com.data.repository.message

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Session cleanup stops current work without destroying the observer needed for the next login. */
internal class SessionListenerBinding(
    scope: CoroutineScope,
    accounts: Flow<String?>,
    private val onStop: () -> Unit,
    onStart: suspend CoroutineScope.(String) -> Unit
) {
    private val active = AtomicReference<Job?>(null)

    init {
        scope.launch {
            // Do not deduplicate account IDs: an emission for the same account
            // can carry renewed credentials that its transport needs to reload.
            accounts.collectLatest { raw ->
                stopActive()
                val account = raw?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return@collectLatest
                val sessionJob = scope.launch(start = CoroutineStart.LAZY) {
                    onStart(account)
                    awaitCancellation()
                }
                active.set(sessionJob)
                sessionJob.start()
                try {
                    sessionJob.join()
                } finally {
                    // Await child cleanup before the next account can bind its transport.
                    withContext(NonCancellable) { sessionJob.cancelAndJoin() }
                    active.compareAndSet(sessionJob, null)
                }
            }
        }
    }

    fun stopActive() {
        active.getAndSet(null)?.cancel()
        onStop()
    }
}
