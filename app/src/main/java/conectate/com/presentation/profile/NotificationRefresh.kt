package conectate.com.presentation.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Merge a burst, let its read finish, and drain any events received during that read. */
internal class NotificationRefresh(
    private val scope: CoroutineScope,
    private val refresh: suspend () -> Unit,
    private val failed: (Exception) -> Unit,
    private val windowMs: Long = 300L,
) {
    private class Pass { var job: Job? = null }
    private var active: Pass? = null
    private var pending = false

    fun request() {
        pending = true
        if (active?.job?.isActive == true) return
        val pass = Pass()
        active = pass
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (active === pass && pending) {
                    delay(windowMs)
                    currentCoroutineContext().ensureActive()
                    if (active !== pass) return@launch
                    pending = false
                    refresh()
                    currentCoroutineContext().ensureActive()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failed(error) }
            finally {
                if (active === pass) {
                    active = null
                    if (pending) request()
                }
            }
        }
        pass.job = job
        job.start()
    }

    fun cancel() {
        val previous = active
        active = null
        pending = false
        previous?.job?.cancel()
    }
}
