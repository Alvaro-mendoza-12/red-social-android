package conectate.com.core.concurrency

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Cancel every operation of the previous account before a retained screen is reused. */
internal class AccountTasks(private val owner: CoroutineScope) {
    var scope = newScope()
        private set
    private fun newScope() = CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job]))
    fun reset() { scope.cancel(); scope = newScope() }
    fun close() { scope.cancel() }
}
