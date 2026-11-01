package conectate.com.presentation.messages

import conectate.com.data.repository.collection.CollectionCreationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class CollectionCreationRecoveryState(
    val loading: Boolean = true,
    val pending: CollectionCreationRequest? = null,
    val readFailed: Boolean = false,
) {
    val showRecovery get() = loading || pending != null || readFailed
}

/** Read failures must not expose an empty, editable form that could create another room. */
internal class CollectionCreationRecovery(
    private val session: CollectionCreationSession,
    private val readPending: suspend () -> CollectionCreationRequest?,
    private val onError: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(CollectionCreationRecoveryState())
    val state = mutableState.asStateFlow()
    private var revision = 0L

    suspend fun refresh() {
        session.requireCurrent()
        val read = ++revision
        mutableState.value = mutableState.value.copy(loading = true)
        try {
            val pending = readPending()
            currentCoroutineContext().ensureActive()
            session.requireCurrent()
            if (read == revision) mutableState.value = CollectionCreationRecoveryState(loading = false, pending = pending)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (read == revision && session.isCurrent()) {
                mutableState.value = CollectionCreationRecoveryState(loading = false, readFailed = true)
                onError("No pudimos recuperar la creación pendiente. Vuelve a intentarlo.")
            }
        }
    }
}
