package conectate.com.presentation.gaming

import conectate.com.data.model.PlayMatchPlayersResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class PlayMatchPlayerQuery(
    val text: String,
    val game: String?,
    val platform: String?,
    val region: String?,
    val cursor: String? = null,
    val availableNow: Boolean = false,
)

/** Search, debounce and pagination share a generation, so pages cannot cross filters. */
internal class PlayMatchDiscoveryController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlayMatchUiState>,
    private val fetch: suspend (PlayMatchPlayerQuery) -> Result<PlayMatchPlayersResponse>,
) {
    private var generation = 0L
    private var job: Job? = null
    private var paging: Job? = null

    fun search(text: String, game: String?, platform: String?, debounce: Boolean = false) {
        val version = ++generation
        job?.cancel()
        paging?.cancel()
        // Persisted input and its cursor must refer to the same normalized query.
        val clean = text.take(120).trim()
        val query = PlayMatchPlayerQuery(clean, game, platform, state.value.regionFilter, availableNow = state.value.availableNowOnly)
        state.update { it.copy(query = text.take(120), gameFilter = game, platformFilter = platform,
            players = emptyList(), nextCursor = null, isSearchingPlayers = true, error = null) }
        job = scope.launch {
            if (debounce) delay(if (clean.isBlank()) 150 else 400)
            val result = request(query)
            if (version != generation) return@launch
            result.fold(
                onSuccess = { response -> state.update { it.copy(players = response.items.distinctBy { player -> player.userId },
                    nextCursor = response.nextCursor, isSearchingPlayers = false) } },
                onFailure = { error -> state.update { it.copy(isSearchingPlayers = false, error = PlayMatchUiPolicy.safeError(error)) } },
            )
        }
    }

    fun loadMore() {
        val snapshot = state.value
        val cursor = snapshot.nextCursor ?: return
        if (snapshot.isSearchingPlayers) return
        val version = generation
        val query = PlayMatchPlayerQuery(snapshot.query.trim(), snapshot.gameFilter, snapshot.platformFilter,
            snapshot.regionFilter, cursor, snapshot.availableNowOnly)
        state.update { it.copy(isSearchingPlayers = true) }
        paging = scope.launch {
            val result = request(query)
            if (version != generation) return@launch
            result.fold(
                onSuccess = { response -> state.update { it.copy(
                    players = (it.players + response.items).distinctBy { player -> player.userId },
                    nextCursor = response.nextCursor?.takeUnless { next -> next == cursor }, isSearchingPlayers = false) } },
                onFailure = { error -> state.update { it.copy(isSearchingPlayers = false, error = PlayMatchUiPolicy.safeError(error)) } },
            )
        }
    }

    private suspend fun request(query: PlayMatchPlayerQuery): Result<PlayMatchPlayersResponse> = try {
        withTimeoutOrNull(12_000) { fetch(query) }
            ?: Result.failure(IllegalStateException("La búsqueda tardó demasiado. Inténtalo otra vez."))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
