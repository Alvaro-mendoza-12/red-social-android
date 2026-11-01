package conectate.com.presentation.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class AdminAccessDecision(val granted: Boolean = false, val unavailable: Boolean = false)

/** A successful HTTP lookup can still explicitly deny administrative access. */
internal class AdminAccessCheck(
    private val scope: () -> CoroutineScope,
    private val account: () -> String?,
    private val request: suspend (String) -> Result<Boolean>,
    private val publish: (AdminAccessDecision) -> Unit,
    private val timeoutMillis: Long = 10_000,
) {
    private var revision = 0L
    private var job: Job? = null

    fun refresh() {
        val version = ++revision
        job?.cancel()
        publish(AdminAccessDecision())
        val owner = account()?.takeIf(String::isNotBlank) ?: return
        fun current() = version == revision && owner == account()
        job = scope().launch {
            try {
                val result = withTimeoutOrNull(timeoutMillis) { request(owner) }
                currentCoroutineContext().ensureActive()
                (result?.exceptionOrNull() as? CancellationException)?.let { throw it }
                if (current()) publish(AdminAccessDecision(
                    granted = result?.getOrNull() == true,
                    unavailable = result == null || result.isFailure,
                ))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) publish(AdminAccessDecision(unavailable = true)) }
        }
    }
}
