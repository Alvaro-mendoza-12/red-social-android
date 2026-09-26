package conectate.com.presentation.messages

import conectate.com.data.model.ScheduledMessageJob
import conectate.com.data.repository.message.ScheduledMessagesKey
import conectate.com.data.repository.message.ScheduledMessagesSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ScheduledMessagesUiState(
    val jobs: List<ScheduledMessageJob> = emptyList(),
    val isLoading: Boolean = false,
    val hasRemoteSnapshot: Boolean = false,
    val error: String? = null,
    val legacyJobsNeedOwner: Boolean = false,
    val cancellingIds: Set<String> = emptySet(),
)

/** Optional dialog state; never sends read failures to the conversation's global snackbar. */
internal class ScheduledMessagesController(
    private val scope: CoroutineScope,
    private val cached: suspend (ScheduledMessagesKey) -> ScheduledMessagesSnapshot,
    private val refresh: suspend (ScheduledMessagesKey) -> Result<ScheduledMessagesSnapshot>,
    private val isCurrent: (ScheduledMessagesKey) -> Boolean,
) {
    private val mutableState = MutableStateFlow(ScheduledMessagesUiState())
    val state = mutableState.asStateFlow()
    private var key: ScheduledMessagesKey? = null
    private var job: Job? = null
    private var generation = 0L

    fun load(target: ScheduledMessagesKey, refreshRemote: Boolean) {
        job?.cancel()
        val request = ++generation
        if (key != target) mutableState.value = ScheduledMessagesUiState()
        key = target
        job = scope.launch {
            fun active() = generation == request && key == target && isCurrent(target)
            try {
                val local = cached(target)
                if (!active()) return@launch
                mutableState.value = updatedState(local, refreshRemote)
                if (!refreshRemote) return@launch
                val result = refresh(target)
                if (!active()) return@launch
                result.fold(
                    onSuccess = { mutableState.value = updatedState(it, false) },
                    onFailure = { mutableState.value = mutableState.value.copy(isLoading = false, error = READ_ERROR) },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (active()) mutableState.value = mutableState.value.copy(isLoading = false, error = READ_ERROR)
            } finally {
                if (active()) mutableState.value = mutableState.value.copy(isLoading = false)
            }
        }
    }

    fun stop() {
        ++generation
        job?.cancel()
        job = null
        mutableState.value = mutableState.value.copy(isLoading = false)
    }

    fun clear() {
        stop()
        key = null
        mutableState.value = ScheduledMessagesUiState()
    }

    fun showActionError(target: ScheduledMessagesKey, message: String) {
        if (key == target && isCurrent(target)) mutableState.value = mutableState.value.copy(error = message)
    }

    fun beginCancellation(target: ScheduledMessagesKey, id: String): Boolean {
        if (key != target || !isCurrent(target) || id in mutableState.value.cancellingIds) return false
        mutableState.value = mutableState.value.copy(cancellingIds = mutableState.value.cancellingIds + id)
        return true
    }

    fun finishCancellation(target: ScheduledMessagesKey, id: String) {
        if (key == target && isCurrent(target)) {
            mutableState.value = mutableState.value.copy(cancellingIds = mutableState.value.cancellingIds - id)
        }
    }

    private fun updatedState(snapshot: ScheduledMessagesSnapshot, loading: Boolean) = ScheduledMessagesUiState(
        jobs = snapshot.jobs,
        isLoading = loading,
        hasRemoteSnapshot = snapshot.hasRemoteSnapshot,
        legacyJobsNeedOwner = snapshot.legacyJobsNeedOwner,
        cancellingIds = mutableState.value.cancellingIds,
    )

    companion object {
        const val READ_ERROR = "No se pudo actualizar la lista. Lo guardado sigue disponible. Revisa tu conexión y reintenta."
    }
}
