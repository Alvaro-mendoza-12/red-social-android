package conectate.com.presentation.auth

import conectate.com.core.network.runReadWithDeadline
import conectate.com.data.localfallback.GuestComment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class GuestCommentsVisit(val kind: String, val id: String)

internal sealed interface GuestCommentsState {
    val visit: GuestCommentsVisit

    data class Loading(override val visit: GuestCommentsVisit) : GuestCommentsState
    data class Ready(override val visit: GuestCommentsVisit, val comments: List<GuestComment>) : GuestCommentsState
    data class Failed(override val visit: GuestCommentsVisit, val message: String) : GuestCommentsState
}

internal class GuestComments(
    private val scope: CoroutineScope,
    private val read: suspend (String, String) -> List<GuestComment>,
) {
    private val current = MutableStateFlow<GuestCommentsState?>(null)
    val state = current.asStateFlow()
    private var loadJob: Job? = null

    fun open(kind: String, id: String) = load(GuestCommentsVisit(kind, id))

    fun retry(visit: GuestCommentsVisit) {
        val failed = current.value as? GuestCommentsState.Failed ?: return
        if (failed.visit === visit) load(visit)
    }

    fun close(visit: GuestCommentsVisit? = current.value?.visit) {
        if (visit == null || current.value?.visit !== visit) return
        current.value = null
        loadJob?.cancel()
        loadJob = null
    }

    private fun load(visit: GuestCommentsVisit) {
        loadJob?.cancel()
        current.value = GuestCommentsState.Loading(visit)
        loadJob = scope.launch {
            val response = runReadWithDeadline(12_000) { read(visit.kind, visit.id) }
            currentCoroutineContext().ensureActive()
            current.value = response.fold(
                onSuccess = { rows -> GuestCommentsState.Ready(visit, rows.distinctBy(GuestComment::id).take(100)) },
                onFailure = { error ->
                    val message = (error as? IllegalStateException)?.message
                        ?: "No pudimos cargar los comentarios. Revisa tu conexión y vuelve a intentarlo."
                    GuestCommentsState.Failed(visit, message)
                },
            )
        }
    }
}
