package conectate.com.presentation.gaming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.*
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.PlayMatchRepository
import conectate.com.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayMatchUiState(
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val capabilities: PlayMatchCapabilities = PlayMatchCapabilities(),
    val catalog: List<PlayMatchGameCatalogItem> = emptyList(),
    val profile: PlayMatchProfile? = null,
    val preferences: PlayMatchPreferences? = null,
    val players: List<PlayMatchProfile> = emptyList(),
    val nextCursor: String? = null,
    val connections: List<PlayMatchConnection> = emptyList(),
    val playerDetail: PlayMatchProfile? = null,
    val session: PlayMatchSession? = null,
    val matches: List<PlayMatchMatch> = emptyList(),
    val query: String = "",
    val gameFilter: String? = null,
    val platformFilter: String? = null,
    val regionFilter: String? = null,
    val error: String? = null,
    val message: String? = null,
)

@HiltViewModel
class PlayMatchViewModel @Inject constructor(
    private val repository: PlayMatchRepository,
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PlayMatchUiState())
    val state: StateFlow<PlayMatchUiState> = _state.asStateFlow()
    private var pollingJob: Job? = null
    private var inboxJob: Job? = null

    init { refreshHome() }

    fun refreshHome() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.capabilities().fold(
                onSuccess = { capabilities ->
                    _state.update { it.copy(capabilities = capabilities) }
                    if (!capabilities.enabled) {
                        _state.update { it.copy(isLoading = false) }
                        return@fold
                    }
                    val catalog = repository.catalog().getOrElse { emptyListResponse(it) }
                    val profile = repository.profile().getOrElse { profileFailure(it) }
                    val players = repository.players().getOrElse { playersFailure(it) }
                    val connections = repository.connections().getOrElse { PlayMatchConnectionsResponse() }
                    val matches = repository.matches().getOrElse { PlayMatchMatchesResponse() }
                    _state.update {
                        it.copy(
                            isLoading = false,
                            catalog = catalog.items,
                            profile = profile?.profile,
                            preferences = profile?.preferences,
                            players = players.items,
                            nextCursor = players.nextCursor,
                            connections = connections.items,
                            matches = matches.items,
                        )
                    }
                    startInboxPolling()
                },
                onFailure = { fail(it) },
            )
        }
    }

    fun search(query: String = _state.value.query, gameId: String? = _state.value.gameFilter, platform: String? = _state.value.platformFilter) {
        _state.update { it.copy(query = query, gameFilter = gameId, platformFilter = platform, isWorking = true, error = null) }
        viewModelScope.launch {
            repository.players(query, gameId, platform, _state.value.regionFilter).fold(
                onSuccess = { response -> _state.update { it.copy(isWorking = false, players = response.items, nextCursor = response.nextCursor) } },
                onFailure = { fail(it, loading = false) },
            )
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query.take(120)) }

    fun loadMore() {
        val cursor = _state.value.nextCursor ?: return
        if (_state.value.isWorking) return
        _state.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            repository.players(_state.value.query, _state.value.gameFilter, _state.value.platformFilter, _state.value.regionFilter, cursor).fold(
                onSuccess = { response -> _state.update { current -> current.copy(isWorking = false, players = current.players + response.items, nextCursor = response.nextCursor) } },
                onFailure = { fail(it, loading = false) },
            )
        }
    }

    fun loadProfile() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val capabilities = repository.capabilities().getOrElse { return@launch fail(it) }
            if (!capabilities.enabled) {
                _state.update { it.copy(isLoading = false, capabilities = capabilities) }
                return@launch
            }
            val catalog = repository.catalog().getOrElse { return@launch fail(it) }
            repository.profile().fold(
                onSuccess = { response -> _state.update { it.copy(isLoading = false, capabilities = capabilities, catalog = catalog.items, profile = response.profile, preferences = response.preferences) } },
                onFailure = { fail(it) },
            )
        }
    }

    fun saveProfile(request: UpdatePlayMatchProfileRequest) = work("Perfil gamer actualizado.", request = { repository.updateProfile(request) }, onSuccess = { response ->
        _state.update { it.copy(profile = response.profile, preferences = response.preferences) }
    })

    fun saveAvailability(entries: List<PlayMatchAvailability>) = work("Disponibilidad guardada.", request = { repository.saveAvailability(entries) }, onSuccess = { response ->
        _state.update { current -> current.copy(profile = current.profile?.copy(availability = response.items)) }
    })

    fun savePreferences(request: SavePlayMatchPreferencesRequest) = work("Filtros guardados.", request = { repository.savePreferences(request) }, onSuccess = { response ->
        _state.update { it.copy(preferences = response.preferences) }
    })

    fun saveGame(request: SavePlayMatchGameRequest, onDone: () -> Unit) = work("Juego guardado.", request = { repository.saveGame(request) }, onSuccess = { response ->
        _state.update { current -> current.copy(profile = current.profile?.let { profile -> profile.copy(games = profile.games.filterNot { it.id == response.game.id } + response.game) }) }
        onDone()
    })

    fun deleteGame(id: String) = work("Juego eliminado.", request = { repository.deleteGame(id) }, onSuccess = {
        _state.update { current -> current.copy(profile = current.profile?.let { profile -> profile.copy(games = profile.games.filterNot { it.id == id }) }) }
    })

    fun loadPlayer(id: String) {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null, playerDetail = null) }
            repository.player(id).fold(
                onSuccess = { player -> _state.update { it.copy(isLoading = false, playerDetail = player) } },
                onFailure = { fail(it) },
            )
        }
    }

    fun connect(playerId: String) = work("Solicitud enviada.", request = { repository.connect(playerId) }, onSuccess = { refreshConnections() })
    fun decideConnection(id: String, decision: String) = work("Solicitud actualizada.", request = { repository.decideConnection(id, decision) }, onSuccess = { refreshConnections() })

    fun startMatchmaking(criteria: PlayMatchCriteria = PlayMatchCriteria()) {
        if (_state.value.isWorking) return
        _state.update { it.copy(isWorking = true, error = null, message = null) }
        viewModelScope.launch {
            repository.start(criteria).fold(
                onSuccess = { response ->
                    _state.update { it.copy(isWorking = false, session = response.session) }
                    if (response.session.status == "SEARCHING") pollSession(response.session.id)
                },
                onFailure = { fail(it, loading = false) },
            )
        }
    }

    fun loadSession(id: String) {
        pollingJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            repository.session(id).fold(
                onSuccess = { response ->
                    _state.update { it.copy(isLoading = false, session = response.session) }
                    if (response.session.status in setOf("SEARCHING", "MATCH_FOUND")) pollSession(id)
                },
                onFailure = { fail(it) },
            )
        }
    }

    fun cancelSession() {
        val id = _state.value.session?.id ?: return
        work("Búsqueda cancelada.", request = { repository.cancel(id) }, onSuccess = { response ->
            pollingJob?.cancel()
            _state.update { it.copy(session = response.session) }
        })
    }

    fun decideMatch(decision: String) {
        val match = _state.value.session?.match ?: return
        work(if (decision == "ACCEPT") "Aceptación enviada." else "Buscaremos otra persona.", request = { repository.decideMatch(match.id, decision) }, onSuccess = {
            if (decision == "SKIP") {
                _state.update { current -> current.copy(session = current.session?.copy(status = "SEARCHING", match = null)) }
                _state.value.session?.id?.let(::pollSession)
            } else {
                _state.value.session?.id?.let(::pollSession)
            }
        })
    }

    fun decideMatchById(matchId: String, decision: String) {
        work(
            if (decision == "ACCEPT") "Aceptación enviada." else "Match descartado.",
            request = { repository.decideMatch(matchId, decision) },
            onSuccess = { refreshMatchInbox() },
        )
    }

    fun blockPlayer(id: String, onDone: () -> Unit) = work("Usuario bloqueado.", request = { userRepository.blockUser(id) }, onSuccess = {
        _state.update { current -> current.copy(players = current.players.filterNot { it.userId == id }, playerDetail = null) }
        onDone()
    })

    fun reportPlayer(id: String, name: String, reason: String) = work(
        "Reporte enviado para revisión.",
        request = { messageRepository.submitReport(id, name, reason, "Reporte enviado desde PlayMatch.", null) },
        onSuccess = { },
    )

    fun clearNotice() = _state.update { it.copy(error = null, message = null) }

    private fun pollSession(id: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                delay(2_000)
                val result = repository.session(id)
                if (result.isFailure) {
                    val error = result.exceptionOrNull() ?: IllegalStateException("PLAYMATCH_REQUEST_FAILED")
                    _state.update { it.copy(error = PlayMatchUiPolicy.safeError(error)) }
                    delay(3_000)
                    continue
                }
                val response = result.getOrThrow()
                _state.update { it.copy(session = response.session) }
                if (response.session.status !in setOf("SEARCHING", "MATCH_FOUND")) break
            }
        }
    }

    private fun refreshConnections() {
        viewModelScope.launch { repository.connections().onSuccess { value -> _state.update { it.copy(connections = value.items) } } }
    }

    private fun refreshMatchInbox() {
        viewModelScope.launch { repository.matches().onSuccess { value -> _state.update { it.copy(matches = value.items) } } }
    }

    private fun startInboxPolling() {
        if (inboxJob?.isActive == true) return
        inboxJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                repository.connections().onSuccess { response -> _state.update { it.copy(connections = response.items) } }
                repository.matches().onSuccess { response -> _state.update { it.copy(matches = response.items) } }
            }
        }
    }

    private fun <T> work(message: String, request: suspend () -> Result<T>, onSuccess: (T) -> Unit) {
        if (_state.value.isWorking) return
        viewModelScope.launch {
            _state.update { it.copy(isWorking = true, error = null, message = null) }
            request().fold(
                onSuccess = { value -> onSuccess(value); _state.update { it.copy(isWorking = false, message = message) } },
                onFailure = { fail(it, loading = false) },
            )
        }
    }

    private fun fail(error: Throwable, loading: Boolean = false) {
        _state.update { it.copy(isLoading = loading, isWorking = false, error = PlayMatchUiPolicy.safeError(error)) }
    }

    private fun emptyListResponse(error: Throwable): PlayMatchCatalogResponse {
        _state.update { it.copy(error = PlayMatchUiPolicy.safeError(error)) }
        return PlayMatchCatalogResponse()
    }

    private fun profileFailure(error: Throwable): PlayMatchProfileResponse? {
        _state.update { it.copy(error = PlayMatchUiPolicy.safeError(error)) }
        return null
    }

    private fun playersFailure(error: Throwable): PlayMatchPlayersResponse {
        _state.update { it.copy(error = PlayMatchUiPolicy.safeError(error)) }
        return PlayMatchPlayersResponse()
    }

    override fun onCleared() {
        pollingJob?.cancel()
        inboxJob?.cancel()
        super.onCleared()
    }
}
