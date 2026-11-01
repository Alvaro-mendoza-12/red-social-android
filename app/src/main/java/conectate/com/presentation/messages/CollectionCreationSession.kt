package conectate.com.presentation.messages

import conectate.com.core.concurrency.AccountTasks
import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The form and its selected image belong to the session that opened them. */
internal class CollectionCreationSession(
    parent: CoroutineScope,
    private val lease: AuthSessionLease,
    private val onError: (String) -> Unit,
) {
    private val tasks = AccountTasks(parent)
    val scope get() = tasks.scope
    val accountId get() = lease.accountId
    private val current = MutableStateFlow(lease.isCurrent())
    val sessionCurrent = current.asStateFlow()
    private val preparing = MutableStateFlow(false)
    val preparingImage = preparing.asStateFlow()
    private val image = MutableStateFlow<ByteArray?>(null)
    val selectedImageBytes = image.asStateFlow()
    private var imageJob: Job? = null
    private var imageRevision = 0L

    fun isCurrent() = current.value && lease.isCurrent()
    fun requireCurrent() {
        lease.requireCurrent()
        if (!current.value) throw CancellationException("Vuelve a abrir este formulario.")
    }

    fun sessionChanged() { if (!lease.isCurrent()) close() }

    fun setImageBytes(bytes: ByteArray?) {
        if (!isCurrent()) return
        imageRevision++
        imageJob?.cancel()
        preparing.value = false
        image.value = bytes
    }

    fun prepareImage(loader: suspend () -> ByteArray?) {
        if (!isCurrent()) return
        val revision = ++imageRevision
        imageJob?.cancel()
        preparing.value = true
        imageJob = scope.launch {
            try {
                val bytes = loader()
                currentCoroutineContext().ensureActive()
                requireCurrent()
                if (revision != imageRevision) return@launch
                if (bytes == null || bytes.isEmpty()) onError("No pudimos preparar ese archivo. Elige otra copia.")
                else image.value = bytes
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (isCurrent() && revision == imageRevision) onError("No pudimos preparar ese archivo. Elige otra copia.")
            } finally {
                if (isCurrent() && revision == imageRevision) preparing.value = false
            }
        }
    }

    fun close() {
        current.value = false
        imageRevision++
        tasks.close()
        image.value = null
        preparing.value = false
    }
}
