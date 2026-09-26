package conectate.com.presentation.social

import conectate.com.core.network.InteractiveReadDeadlines
import conectate.com.core.network.ReadDeadlineExceededException
import conectate.com.core.network.runReadWithDeadline
import conectate.com.data.music.ApprovedMomentMusic
import conectate.com.data.music.MomentMusicCatalogPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class MusicCatalogUiState(
    val items: List<ApprovedMomentMusic> = emptyList(),
    val query: String = "",
    val category: String = "Todo",
    val section: String = "search",
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val loadMoreError: String? = null,
    val actionMessage: String? = null,
)

internal fun momentMusicCatalogError(error: Throwable): String = when {
    error is ReadDeadlineExceededException ->
        "La música está tardando en responder. Puedes volver a intentarlo."
    else -> when (error.message?.trim()?.uppercase()) {
        "NOT_FOUND", "MUSIC_ROUTE_NOT_FOUND" ->
            "El catálogo comunitario todavía no está disponible en este servidor."
        "UNAUTHORIZED", "AUTH_REQUIRED" ->
            "Vuelve a iniciar sesión para consultar la música de la comunidad."
        else -> error.message
            ?.takeUnless { it.matches(Regex("[A-Z][A-Z0-9_]{2,}")) }
            ?: "No se pudo cargar la música de la comunidad. Revisa la conexión e inténtalo de nuevo."
    }
}

/** Main-thread controller, scoped to the editor. No catalog is shared between accounts. */
internal class MomentMusicCatalogLoader(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<MusicCatalogUiState>,
    private val fetch: suspend (query: String, category: String, cursor: String?, section: String) -> Result<MomentMusicCatalogPage>,
    private val deadlineMillis: Long = InteractiveReadDeadlines.FirstPageMillis,
    private val debounceMillis: Long = 350L,
) {
    private var generation = 0L
    private var searchJob: Job? = null
    private var pageJob: Job? = null
    private val consumedCursors = mutableSetOf<String>()

    fun search(query: String, category: String, section: String) {
        val requestGeneration = ++generation
        searchJob?.cancel()
        pageJob?.cancel()
        val cleanQuery = query.take(80)
        val current = state.value
        val sameSearch = current.query == cleanQuery && current.category == category && current.section == section
        if (!sameSearch) consumedCursors.clear()
        // A same-search refresh must not blank songs already on screen. A different
        // search never shows another query's songs while its first page is pending.
        state.value = (if (sameSearch) current else MusicCatalogUiState(
            query = cleanQuery,
            category = category,
            section = section,
        )).copy(isLoading = true, isLoadingMore = false, error = null, loadMoreError = null, actionMessage = null)
        searchJob = scope.launch {
            if (!sameSearch && cleanQuery.isNotBlank()) delay(debounceMillis)
            val result = runReadWithDeadline(deadlineMillis) {
                fetch(cleanQuery, category, null, section).getOrThrow()
            }
            currentCoroutineContext().ensureActive()
            if (requestGeneration != generation) return@launch
            result.fold(
                onSuccess = { page ->
                    consumedCursors.clear()
                    state.value = state.value.copy(
                        items = page.items.distinctBy { it.id },
                        nextCursor = page.nextCursor?.takeIf(String::isNotBlank),
                        isLoading = false,
                        error = null,
                    )
                },
                onFailure = { error ->
                    state.value = state.value.copy(isLoading = false, error = momentMusicCatalogError(error))
                },
            )
        }
    }

    fun loadMore() {
        val current = state.value
        val cursor = current.nextCursor?.takeIf(String::isNotBlank) ?: return
        if (current.isLoading || current.isLoadingMore) return
        val requestGeneration = generation
        state.value = current.copy(isLoadingMore = true, loadMoreError = null)
        pageJob = scope.launch {
            val result = runReadWithDeadline(deadlineMillis) {
                fetch(current.query, current.category, cursor, current.section).getOrThrow()
            }
            currentCoroutineContext().ensureActive()
            if (requestGeneration != generation) return@launch
            result.fold(
                onSuccess = { page ->
                    consumedCursors += cursor
                    state.value = state.value.copy(
                        items = (state.value.items + page.items).distinctBy { it.id },
                        // A repeated/cyclic cursor must not keep requesting the same page.
                        nextCursor = page.nextCursor?.takeIf { it.isNotBlank() && it !in consumedCursors },
                        isLoadingMore = false,
                        loadMoreError = null,
                    )
                },
                onFailure = { error ->
                    state.value = state.value.copy(
                        isLoadingMore = false,
                        loadMoreError = momentMusicCatalogError(error),
                    )
                },
            )
        }
    }
}
