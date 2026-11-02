package conectate.com.presentation.profile

import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

/** Every request owns its account lease and UI slot, including cancellation cleanup. */
internal class NotificationRequests(
    private val scope: CoroutineScope,
    private val captureOwner: () -> AuthSessionLease,
    private val timeoutMs: Long = 12_000L,
) {
    private class Request(val owner: AuthSessionLease, val finished: () -> Unit) {
        var job: Job? = null
    }
    private val requests = mutableMapOf<String, Request>()

    fun <T : Any> run(
        key: String,
        replace: Boolean = true,
        started: () -> Unit = {},
        action: suspend (AuthSessionLease) -> Result<T>,
        success: (T) -> Unit,
        failure: (Throwable) -> Unit,
        cancelled: () -> Unit = {},
        finished: () -> Unit = {},
    ): Job? {
        val owner = captureOwner()
        if (!owner.isCurrent()) {
            cancel(key)
            failure(IllegalStateException("Inicia sesión para continuar."))
            return null
        }
        val previous = requests[key]
        if (!replace && previous?.owner?.isCurrent() == true) return previous.job
        val request = Request(owner, finished)
        requests[key] = request
        previous?.job?.cancel()
        started()
        fun current() = requests[key] === request && owner.isCurrent()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                owner.requireCurrent()
                val result = withTimeoutOrNull(timeoutMs) { action(owner) }
                    ?: Result.failure(TimeoutException("Notification request timed out"))
                currentCoroutineContext().ensureActive()
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                if (current()) result.fold(success, failure)
            } catch (cancel: CancellationException) {
                // A cancelled/replaced screen is silent. An interrupted current load can offer retry.
                if (current() && currentCoroutineContext().isActive) cancelled()
                throw cancel
            } catch (error: Exception) {
                if (current()) failure(error)
            } finally {
                if (requests[key] === request) {
                    requests.remove(key)
                    finished()
                }
            }
        }
        request.job = job
        if (requests[key] === request) job.start() else job.cancel()
        return job
    }

    fun cancel(key: String) {
        val request = requests.remove(key) ?: return
        request.job?.cancel()
        request.finished()
    }

    fun cancelAll() {
        val previous = requests.values.toList()
        requests.clear()
        previous.forEach { it.job?.cancel(); it.finished() }
    }
}
