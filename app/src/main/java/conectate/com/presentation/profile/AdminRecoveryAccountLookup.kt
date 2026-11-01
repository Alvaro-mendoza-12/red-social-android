package conectate.com.presentation.profile

import conectate.com.core.network.runReadWithDeadline
import conectate.com.data.localfallback.AdminRecoveryAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Clearing a case invalidates its lookup as well as its currently visible results. */
internal class AdminRecoveryAccountLookup(
    private val scope: () -> CoroutineScope,
    private val isCurrentSession: () -> Boolean,
    private val read: suspend (String) -> Result<List<AdminRecoveryAccount>>,
    private val publish: (List<AdminRecoveryAccount>) -> Unit,
    private val failure: (Throwable) -> Unit,
    private val timeoutMillis: Long = 10_000,
) {
    private var revision = 0L
    private var job: Job? = null

    fun clear() {
        revision++
        job?.cancel()
        job = null
        publish(emptyList())
    }

    fun search(query: String) {
        clear()
        val clean = query.trim().take(120)
        if (clean.length < 3 || !isCurrentSession()) return
        val request = revision
        job = scope().launch {
            if (!isCurrentSession() || request != revision) return@launch
            val result = runReadWithDeadline(timeoutMillis) { read(clean).getOrThrow() }
            currentCoroutineContext().ensureActive()
            if (isCurrentSession() && request == revision) result.fold(publish, failure)
        }
    }
}
