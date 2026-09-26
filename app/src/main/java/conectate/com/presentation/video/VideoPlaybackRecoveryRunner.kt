package conectate.com.presentation.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One recovery at a time; slow discovery must never seek a different page. */
internal class VideoPlaybackRecoveryRunner(private val scope: CoroutineScope) {
    private var revision = 0L
    private var job: Job? = null

    fun cancel() {
        revision++
        job?.cancel()
        job = null
    }

    fun <T : Any> schedule(
        delayMs: Long,
        isCurrent: () -> Boolean,
        resolve: suspend () -> T?,
        apply: (T) -> Unit,
        onFailure: () -> Unit,
    ) {
        cancel()
        val requestRevision = revision
        fun isActiveTarget() = revision == requestRevision && isCurrent()
        job = scope.launch {
            try {
                delay(delayMs)
                if (!isActiveTarget()) return@launch
                val result = withTimeoutOrNull(12_000L) { resolve() }
                currentCoroutineContext().ensureActive()
                if (!isActiveTarget()) return@launch
                if (result != null) apply(result) else onFailure()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isActiveTarget()) onFailure()
            }
        }
    }
}
