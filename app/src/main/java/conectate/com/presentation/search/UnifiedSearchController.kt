package conectate.com.presentation.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class SearchContentPage<C>(val items: List<C>, val nextOffset: Int? = null)

/** Keeps late replies and cancellation from overwriting a newer search. */
internal class UnifiedSearchController<U, C>(
    private val scope: CoroutineScope,
    private val findUsers: suspend (String) -> Result<List<U>>,
    private val findContent: suspend (String) -> Result<List<C>>,
    private val debounceMs: Long = 350L,
    private val findContentPage: (suspend (String, Int) -> Result<SearchContentPage<C>>)? = null,
    private val contentKey: (C) -> Any? = { it },
) {
    private val _users = MutableStateFlow<List<U>>(emptyList())
    val users = _users.asStateFlow()
    private val _content = MutableStateFlow<List<C>>(emptyList())
    val content = _content.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()
    private val _usersError = MutableStateFlow<String?>(null)
    val usersError = _usersError.asStateFlow()
    private val _contentError = MutableStateFlow<String?>(null)
    val contentError = _contentError.asStateFlow()
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore = _loadingMore.asStateFlow()
    private val _hasMoreContent = MutableStateFlow(false)
    val hasMoreContent = _hasMoreContent.asStateFlow()
    private var nextContentOffset: Int? = null
    private var moreJob: Job? = null
    private var generation = 0L
    private var job: Job? = null
    private var query = ""

    fun search(rawQuery: String, immediate: Boolean = false) {
        val normalized = rawQuery.trim().removePrefix("@").trim().take(120)
        if (normalized == query && job?.isActive == true) return
        clear()
        query = normalized
        if (normalized.length < 2) return
        val request = generation
        _loading.value = true
        job = scope.launch {
            try {
                if (!immediate) delay(debounceMs)
                val usersJob = launch {
                    val result = safely { findUsers(normalized) }
                    if (request == generation) {
                        _users.value = result.getOrDefault(emptyList())
                        _usersError.value = if (result.isFailure)
                            "No pudimos buscar personas. Revisa tu conexión y vuelve a intentarlo." else null
                    }
                }
                val contentJob = launch {
                    val result = safely {
                        findContentPage?.invoke(normalized, 0)
                            ?: findContent(normalized).map { SearchContentPage(it) }
                    }
                    if (request == generation) {
                        val page = result.getOrNull()
                        _content.value = page?.items.orEmpty().distinctBy(contentKey)
                        nextContentOffset = page?.nextOffset?.takeIf { it > 0 }
                        _hasMoreContent.value = nextContentOffset != null
                        _contentError.value = if (result.isFailure)
                            "La búsqueda de contenido no está disponible. Puedes seguir buscando personas." else null
                    }
                }
                usersJob.join()
                contentJob.join()
            } finally {
                if (request == generation) _loading.value = false
            }
        }
    }

    fun loadMoreContent() {
        val offset = nextContentOffset ?: return
        val read = findContentPage ?: return
        if (_loading.value || _loadingMore.value || query.length < 2) return
        val request = generation
        val requestedQuery = query
        _loadingMore.value = true
        _contentError.value = null
        moreJob = scope.launch {
            try {
                val result = safely { read(requestedQuery, offset) }
                if (request != generation) return@launch
                result.onSuccess { page ->
                    _content.value = (_content.value + page.items).distinctBy(contentKey)
                    nextContentOffset = page.nextOffset?.takeIf { it > offset }
                    _hasMoreContent.value = nextContentOffset != null
                }.onFailure {
                    _contentError.value = "No pudimos cargar más resultados. Vuelve a intentarlo."
                }
            } finally {
                if (request == generation) _loadingMore.value = false
            }
        }
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        moreJob?.cancel()
        moreJob = null
        nextContentOffset = null
        _loadingMore.value = false
        _hasMoreContent.value = false
        query = ""
        _users.value = emptyList()
        _content.value = emptyList()
        _usersError.value = null
        _contentError.value = null
        _loading.value = false
    }

    private suspend fun <T> safely(block: suspend () -> Result<T>): Result<T> = try {
        (withTimeoutOrNull(12_000L) { block() }
            ?: Result.failure(IllegalStateException("La búsqueda tardó demasiado."))).also { result ->
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
