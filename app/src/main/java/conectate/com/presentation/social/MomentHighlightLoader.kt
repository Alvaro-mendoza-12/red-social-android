package conectate.com.presentation.social

import conectate.com.core.network.InteractiveReadDeadlines
import conectate.com.core.network.runReadWithDeadline
import conectate.com.data.model.MomentHighlightItemPreview
import conectate.com.data.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class MomentHighlightViewerUiState(
    val loading: Boolean = true,
    val items: List<MomentHighlightItemPreview> = emptyList(),
    val error: String? = null,
)

/** Main-thread controller. Author enrichment cannot hold up playback or replace a newer load. */
internal class MomentHighlightLoader(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<MomentHighlightViewerUiState>,
    private val fetchItems: suspend (String) -> Result<List<MomentHighlightItemPreview>>,
    private val fetchAuthors: suspend (List<String>) -> Result<List<User>>,
    private val deadlineMillis: Long = InteractiveReadDeadlines.ProfileMillis,
) {
    private var loadedId: String? = null
    private var generation = 0L
    private var job: Job? = null

    fun load(highlightId: String, retry: Boolean = false) {
        if (!retry && highlightId == loadedId && (state.value.loading || state.value.error == null)) return
        loadedId = highlightId
        val request = ++generation
        job?.cancel()
        state.value = MomentHighlightViewerUiState()
        job = scope.launch {
            val result = runReadWithDeadline(deadlineMillis) { fetchItems(highlightId).getOrThrow() }
            currentCoroutineContext().ensureActive()
            if (request != generation) return@launch
            val items = result.getOrNull()
            if (items == null) {
                state.value = MomentHighlightViewerUiState(loading = false,
                    error = "No se pudo abrir el destacado. Revisa la conexión y vuelve a intentarlo.")
                return@launch
            }
            state.value = MomentHighlightViewerUiState(loading = false, items = items,
                error = if (items.isEmpty()) "Este destacado no tiene momentos disponibles." else null)
            val authorIds = items.map { it.userId.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            if (authorIds.isEmpty()) return@launch
            val profiles = runReadWithDeadline(deadlineMillis) { fetchAuthors(authorIds).getOrThrow() }
                .getOrNull() ?: return@launch
            currentCoroutineContext().ensureActive()
            if (request != generation) return@launch
            val authors = profiles.associateBy { it.id.trim().lowercase() }
            state.value = state.value.copy(items = items.map { item ->
                val author = authors[item.userId.trim().lowercase()] ?: return@map item
                item.copy(username = author.username, userDisplayName = author.display_name
                    ?.takeIf { it.isNotBlank() } ?: author.full_name?.takeIf { it.isNotBlank() },
                    userAvatarUrl = author.avatar_url)
            })
        }
    }
}
