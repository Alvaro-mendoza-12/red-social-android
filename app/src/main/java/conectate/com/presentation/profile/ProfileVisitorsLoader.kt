package conectate.com.presentation.profile

import conectate.com.data.repository.ProfileViewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class ProfileVisitorsState(
    val items: List<ProfileViewer> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val enabled: Boolean = true,
)

/** A completed empty result is distinct from a pending or failed read. */
internal class ProfileVisitorsLoader(
    private val scope: CoroutineScope,
    private val currentAccount: () -> String?,
    private val read: suspend () -> Result<List<ProfileViewer>>,
    private val timeoutMs: Long = 5_000,
) {
    private val mutableState = MutableStateFlow(ProfileVisitorsState())
    val state = mutableState.asStateFlow()
    private var owner: String? = null
    private var revision = 0L
    private var job: Job? = null

    fun accountChanged() {
        val actor = currentAccount()
        if (owner == actor) return
        ++revision
        job?.cancel()
        owner = actor
        mutableState.value = ProfileVisitorsState(loading = actor != null)
    }

    fun refresh(enabled: Boolean) {
        val actor = currentAccount()
        val ticket = ++revision
        job?.cancel()
        val previous = mutableState.value.items.takeIf { owner == actor }.orEmpty()
        owner = actor
        if (!enabled || actor == null) {
            mutableState.value = ProfileVisitorsState(loading = false, enabled = enabled)
            return
        }
        mutableState.value = ProfileVisitorsState(previous, loading = true)
        job = scope.launch {
            try {
                val result = withTimeoutOrNull(timeoutMs) { read() }
                (result?.exceptionOrNull() as? CancellationException)?.let { throw it }
                if (revision != ticket) return@launch
                if (actor != currentAccount()) { accountChanged(); return@launch }
                mutableState.value = when {
                    result == null || result.isFailure -> ProfileVisitorsState(previous, loading = false,
                        error = "No se pudieron cargar las visitas. Inténtalo de nuevo.")
                    else -> ProfileVisitorsState(result.getOrThrow(), loading = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == ticket && actor == currentAccount()) {
                    mutableState.value = ProfileVisitorsState(previous, loading = false,
                        error = "No se pudieron cargar las visitas. Inténtalo de nuevo.")
                }
            }
        }
    }
}
