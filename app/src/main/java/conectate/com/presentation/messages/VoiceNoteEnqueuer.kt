package conectate.com.presentation.messages

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Reserve until durable acceptance, without blocking the next note on its network delivery. */
internal class VoiceNoteEnqueuer(
    private val scope: CoroutineScope,
    private val setBusy: (Boolean) -> Unit,
    private val showError: (String) -> Unit,
    private val callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private var pending: Any? = null

    fun enqueue(
        file: File,
        isCurrent: () -> Boolean,
        send: suspend (onQueued: suspend () -> Unit) -> Result<Unit>,
        onQueued: () -> Unit,
        onUnqueuedFailure: () -> Unit,
    ) {
        if (pending != null || !isCurrent()) return
        val token = Any()
        pending = token
        setBusy(true)
        fun release() {
            if (pending === token) {
                pending = null
                setBusy(false)
            }
        }
        scope.launch {
            var accepted = false
            fun failed() {
                if (!isCurrent()) return
                if (!accepted) onUnqueuedFailure()
                showError(if (accepted) "El audio quedó pendiente. Puedes reintentarlo desde el mensaje."
                    else "No se pudo preparar el envío. Tu grabación sigue disponible para reintentar.")
            }
            try {
                if (!isCurrent()) return@launch
                val result = send {
                    withContext(callbackDispatcher) {
                        currentCoroutineContext().ensureActive()
                        if (!accepted) {
                            accepted = true
                            // The repository has copied this private recording and committed its message.
                            if (isCurrent()) onQueued()
                            file.delete()
                            release()
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                if (result.isFailure || !accepted) failed()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed() }
            finally { release() }
        }
    }
}
