package conectate.com.presentation.messages

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Serialize explicit collection commands; a failed command must not navigate as if it succeeded. */
internal class CollectionActionRunner(
    private val scope: CoroutineScope,
    private val account: () -> String?,
    private val failed: (String) -> Unit,
    private val accountUpdates: Flow<String?>? = null,
) {
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    val busy = pending.asStateFlow()

    fun <T> run(key: String, failureMessage: String, isCurrent: () -> Boolean,
                action: suspend () -> Result<T>, success: (T) -> Unit = {}) {
        val owner = account()?.takeIf { it.isNotBlank() } ?: run { failed("Inicia sesión para continuar."); return }
        if (key in pending.value || !isCurrent()) return
        pending.value += key
        scope.launch {
            try {
                if (account() != owner || !isCurrent()) return@launch
                val result = coroutineScope {
                    val command = async { action() }
                    val watcher = accountUpdates?.let { updates ->
                        launch { updates.first { it != owner }; command.cancel() }
                    }
                    try { command.await() } finally { watcher?.cancel() }
                }
                currentCoroutineContext().ensureActive()
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                if (account() != owner || !isCurrent()) return@launch
                result.fold(success, { failed(failureMessage) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (account() == owner && isCurrent()) failed(failureMessage) }
            finally { pending.value -= key }
        }
    }
}
