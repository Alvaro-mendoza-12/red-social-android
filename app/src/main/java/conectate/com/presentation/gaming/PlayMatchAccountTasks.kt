package conectate.com.presentation.gaming

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** All work tied to one active account is cancelled together before the UI is reused. */
internal class PlayMatchAccountTasks(private val owner: CoroutineScope) {
    var scope = newScope()
        private set
    private fun newScope() = CoroutineScope(owner.coroutineContext + SupervisorJob(owner.coroutineContext[Job]))
    fun reset() { scope.cancel(); scope = newScope() }
    fun close() { scope.cancel() }
}
