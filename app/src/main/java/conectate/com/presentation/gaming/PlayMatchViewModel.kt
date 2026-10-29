package conectate.com.presentation.gaming

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.*
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.PlayMatchRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class PlayMatchUiState(
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val isSearchingPlayers: Boolean = false,
    val availableNowOnly: Boolean = false,
    val capabilities: PlayMatchCapabilities = PlayMatchCapabilities(),
    val catalog: List<PlayMatchGameCatalogItem> = emptyList(),
    val catalogResults: List<PlayMatchGameCatalogItem> = emptyList(),
    val catalogQuery: String = "",
    val catalogNextCursor: String? = null,
    val isCatalogLoading: Boolean = false,
    val isCatalogLoadingMore: Boolean = false,
    val isCatalogSelectionLoading: Boolean = false,
    val catalogError: String? = null,
    val selectedCatalogGame: PlayMatchGameCatalogItem? = null,
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
    private val authService: AuthService,
) : ViewModel() {
    private val _state = MutableStateFlow(PlayMatchUiState())
    val state: StateFlow<PlayMatchUiState> = _state.asStateFlow()
    private var pollingJob: Job? = null
    private var inboxJob: Job? = null
    private var catalogSearchJob: Job? = null
    private var homeJob: Job? = null
    private var playerLoadJob: Job? = null
    private var sessionLoadJob: Job? = null
    private val accountTasks = PlayMatchAccountTasks(viewModelScope)
    private fun newDiscovery() = PlayMatchDiscoveryController(accountTasks.scope, _state) { query ->
        repository.players(query.text, query.game, query.platform, query.region, query.cursor, query.availableNow)
    }
    private var discovery = newDiscovery()
    private var catalogRequestGeneration: Long = 0

    init {
        viewModelScope.launch {
            authService.currentSession.map { it?.user?.id }.distinctUntilChanged().collect { id ->
                accountTasks.reset()
                pollingJob = null
                inboxJob = null
                catalogSearchJob = null
                homeJob = null
                catalogRequestGeneration++
                discovery = newDiscovery()
                _state.value = PlayMatchUiState(isLoading = id != null)
                if (id != null) refreshHome()
            }
        }
    }

    fun refreshHome() {
        if (homeJob?.isActive == true) return
        homeJob = accountTasks.scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val capabilities = boundedRequest { repository.capabilities() }.getOrElse {
                fail(it)
                return@launch
            }
            _state.update { it.copy(capabilities = capabilities, isLoading = false) }
            if (!capabilities.enabled) return@launch
            // Independent typed requests hydrate as soon as each response arrives.
            // A successful empty response clears old rows; a failed refresh preserves them.
            search()
            coroutineScope {
                launch {
                    boundedRequest { repository.popularGames() }.onSuccess { response ->
                        _state.update { it.copy(catalog = response.items) }
                    }
                }
                launch {
                    boundedRequest { repository.profile() }.onSuccess { response ->
                        _state.update { it.copy(profile = response.profile, preferences = response.preferences) }
                    }
                }
                launch {
                    boundedRequest { repository.connections() }.onSuccess { response ->
                        _state.update { it.copy(connections = response.items) }
                    }
                }
                launch {
                    boundedRequest { repository.matches() }.onSuccess { response ->
                        _state.update { it.copy(matches = response.items) }
                    }
                }
            }
            startInboxPolling()
        }
    }

    fun search(query: String = _state.value.query, gameId: String? = _state.value.gameFilter, platform: String? = _state.value.platformFilter) {
        discovery.search(query, gameId, platform)
    }

    fun setQuery(query: String) {
        discovery.search(query, _state.value.gameFilter, _state.value.platformFilter, debounce = true)
    }

    fun setAvailableNow(enabled: Boolean) {
        _state.update { it.copy(availableNowOnly = enabled) }
        search()
    }

    fun clearPlayerFilters() {
        _state.update { it.copy(availableNowOnly = false, regionFilter = null) }
        search(query = "", gameId = null, platform = null)
    }

    fun loadMore() = discovery.loadMore()
    fun loadProfile() {
        accountTasks.scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val capabilities = boundedRequest { repository.capabilities() }.getOrElse { return@launch fail(it) }
            if (!capabilities.enabled) {
                _state.update { it.copy(isLoading = false, capabilities = capabilities) }
                return@launch
            }
            val catalog = boundedRequest { repository.popularGames() }.getOrElse { return@launch fail(it) }
            boundedRequest { repository.profile() }.fold(
                onSuccess = { response -> _state.update { it.copy(isLoading = false, capabilities = capabilities, catalog = catalog.items, profile = response.profile, preferences = response.preferences) } },
                onFailure = { fail(it) },
            )
        }
    }

    fun saveProfile(request: UpdatePlayMatchProfileRequest) = work("Perfil gamer actualizado.", request = { repository.updateProfile(request) }, onSuccess = { response ->
        _state.update { it.copy(profile = response.profile, preferences = response.preferences) }
    })

    fun uploadGamerAvatar(uri: Uri, onUploaded: (String, String) -> Unit) {
        if (_state.value.isWorking) return
        _state.update { it.copy(isWorking = true, error = null) }
        accountTasks.scope.launch {
            userRepository.uploadProfileAvatarMedia(uri).fold(
                onSuccess = { upload ->
                    onUploaded(upload.url, upload.mediaType)
                    _state.update { it.copy(isWorking = false, message = "Avatar gamer preparado. Guarda el perfil para confirmar.") }
                },
                onFailure = { fail(it, loading = false) },
            )
        }
    }

    fun uploadGamerPhoto(uri: Uri) {
        val profile = _state.value.profile ?: return
        if (!_state.value.capabilities.photosEnabled) {
            _state.update { it.copy(error = "Las capturas gamer no están habilitadas en este servidor.") }
            return
        }
        if (profile.photos.size >= 4 || _state.value.isWorking) return
        _state.update { it.copy(isWorking = true, error = null, message = null) }
        accountTasks.scope.launch {
            userRepository.uploadProfileImage(uri).fold(
                onSuccess = { uploadedUrl ->
                    repository.updateProfile(profile.asUpdateRequest(photos = profile.photos + uploadedUrl)).fold(
                        onSuccess = { response ->
                            _state.update { it.copy(isWorking = false, profile = response.profile, preferences = response.preferences, message = "Captura gamer agregada.") }
                        },
                        onFailure = { error ->
                            userRepository.deleteUploadedProfileMedia(uploadedUrl)
                            fail(error, loading = false)
                        },
                    )
                },
                onFailure = { fail(it, loading = false) },
            )
        }
    }

    fun removeGamerPhoto(url: String) {
        val profile = _state.value.profile ?: return
        if (_state.value.isWorking || url !in profile.photos) return
        work(
            message = "Captura gamer eliminada.",
            request = { repository.updateProfile(profile.asUpdateRequest(photos = profile.photos.filterNot { it == url })) },
            onSuccess = { response ->
                _state.update { it.copy(profile = response.profile, preferences = response.preferences) }
                accountTasks.scope.launch { userRepository.deleteUploadedProfileMedia(url) }
            },
        )
    }

    fun saveAvailability(entries: List<PlayMatchAvailability>) = work("Disponibilidad guardada.", request = { repository.saveAvailability(entries) }, onSuccess = { response ->
        _state.update { current -> current.copy(profile = current.profile?.copy(availability = response.items)) }
    })

    fun savePreferences(request: SavePlayMatchPreferencesRequest) = work("Filtros guardados.", request = { repository.savePreferences(request) }, onSuccess = { response ->
        _state.update { it.copy(preferences = response.preferences) }
    })

    fun prepareCatalogSearch() {
        if (_state.value.catalogResults.isNotEmpty() || _state.value.isCatalogLoading) return
        updateCatalogQuery("")
    }

    fun updateCatalogQuery(query: String) {
        val normalized = query.take(100)
        catalogSearchJob?.cancel()
        val generation = ++catalogRequestGeneration
        _state.update {
            it.copy(
                catalogQuery = normalized,
                catalogResults = emptyList(),
                catalogNextCursor = null,
                selectedCatalogGame = null,
                catalogError = null,
                isCatalogLoading = true,
                isCatalogLoadingMore = false,
                isCatalogSelectionLoading = false,
            )
        }
        catalogSearchJob = accountTasks.scope.launch {
            delay(if (normalized.isBlank()) 0 else 350)
            repository.searchGames(normalized).fold(
                onSuccess = { response ->
                    if (generation != catalogRequestGeneration) return@fold
                    _state.update { current -> current.copy(
                        isCatalogLoading = false,
                        catalogResults = response.items,
                        catalogNextCursor = response.nextCursor,
                        catalogError = null,
                    ) }
                },
                onFailure = { error ->
                    if (generation != catalogRequestGeneration) return@fold
                    _state.update { it.copy(isCatalogLoading = false, catalogError = PlayMatchUiPolicy.safeError(error)) }
                },
            )
        }
    }

    fun retryCatalogSearch() {
        val query = _state.value.catalogQuery
        catalogSearchJob?.cancel()
        val generation = ++catalogRequestGeneration
        _state.update { it.copy(isCatalogLoading = true, catalogError = null) }
        catalogSearchJob = accountTasks.scope.launch {
            repository.searchGames(query, force = true).fold(
                onSuccess = { response ->
                    if (generation == catalogRequestGeneration) _state.update { it.copy(
                        isCatalogLoading = false,
                        catalogResults = response.items,
                        catalogNextCursor = response.nextCursor,
                    ) }
                },
                onFailure = { error -> if (generation == catalogRequestGeneration) _state.update { it.copy(isCatalogLoading = false, catalogError = PlayMatchUiPolicy.safeError(error)) } },
            )
        }
    }

    fun loadMoreCatalog() {
        val cursor = _state.value.catalogNextCursor ?: return
        if (_state.value.isCatalogLoading || _state.value.isCatalogLoadingMore) return
        val generation = catalogRequestGeneration
        val query = _state.value.catalogQuery
        _state.update { it.copy(isCatalogLoadingMore = true, catalogError = null) }
        accountTasks.scope.launch {
            repository.searchGames(query, cursor).fold(
                onSuccess = { response ->
                    if (generation != catalogRequestGeneration) return@fold
                    _state.update { current -> current.copy(
                        isCatalogLoadingMore = false,
                        catalogResults = (current.catalogResults + response.items).distinctBy { it.id },
                        catalogNextCursor = response.nextCursor,
                    ) }
                },
                onFailure = { error -> if (generation == catalogRequestGeneration) _state.update { it.copy(isCatalogLoadingMore = false, catalogError = PlayMatchUiPolicy.safeError(error)) } },
            )
        }
    }

    fun selectCatalogGame(game: PlayMatchGameCatalogItem) {
        _state.update { it.copy(selectedCatalogGame = game, catalogError = null, isCatalogSelectionLoading = true) }
        accountTasks.scope.launch {
            repository.gameConfiguration(game.id).fold(
                onSuccess = { response -> _state.update { it.copy(selectedCatalogGame = response.game, isCatalogSelectionLoading = false) } },
                onFailure = { error -> _state.update { it.copy(isCatalogSelectionLoading = false, catalogError = PlayMatchUiPolicy.safeError(error)) } },
            )
        }
    }

    fun saveGame(request: SavePlayMatchGameRequest, onDone: () -> Unit) = work("Juego guardado.", request = { repository.saveGame(request) }, onSuccess = { response ->
        _state.update { current -> current.copy(profile = current.profile?.let { profile -> profile.copy(games = profile.games.filterNot { it.id == response.game.id } + response.game) }) }
        onDone()
    })

    fun deleteGame(id: String) = work("Juego eliminado.", request = { repository.deleteGame(id) }, onSuccess = {
        _state.update { current -> current.copy(profile = current.profile?.let { profile -> profile.copy(games = profile.games.filterNot { it.id == id }) }) }
    })

    fun loadPlayer(id: String) {
        playerLoadJob?.cancel()
        playerLoadJob = accountTasks.scope.launch {
            _state.update { it.copy(isLoading = true, error = null, playerDetail = null) }
            boundedRequest { repository.player(id) }.fold(
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
        accountTasks.scope.launch {
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
        sessionLoadJob?.cancel()
        sessionLoadJob = accountTasks.scope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            boundedRequest { repository.session(id) }.fold(
                onSuccess = { response ->
                    _state.update { it.copy(isLoading = false, session = response.session) }
                    if (response.session.status in setOf("SEARCHING", "MATCH_FOUND")) pollSession(id)
                },
                onFailure = { fail(it) },
            )
        }
    }

    fun cancelSession(onDone: () -> Unit = {}) {
        val current = _state.value.session
        if (current == null) { onDone(); return }
        if (_state.value.isWorking) return
        pollingJob?.cancel()
        _state.update { it.copy(isWorking = true, message = null, error = null) }
        accountTasks.scope.launch {
            boundedRequest { repository.cancel(current.id) }.fold(
                onSuccess = { response ->
                    if (_state.value.session?.id != current.id) return@fold
                    _state.update { it.copy(isWorking = false, session = response.session, message = "Búsqueda cancelada.") }
                    onDone()
                },
                onFailure = { error ->
                    if (_state.value.session?.id != current.id) return@fold
                    _state.update { it.copy(isWorking = false, error = PlayMatchUiPolicy.safeError(error)) }
                    if (_state.value.session?.status in setOf("SEARCHING", "MATCH_FOUND")) pollSession(current.id)
                },
            )
        }
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
        pollingJob = accountTasks.scope.launch {
            while (true) {
                delay(2_000)
                val result = boundedRequest { repository.session(id) }
                if (_state.value.session?.id != id || _state.value.isWorking) continue
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
        accountTasks.scope.launch { repository.connections().onSuccess { value -> _state.update { it.copy(connections = value.items) } } }
    }

    private fun refreshMatchInbox() {
        accountTasks.scope.launch { repository.matches().onSuccess { value -> _state.update { it.copy(matches = value.items) } } }
    }

    private fun startInboxPolling() {
        if (inboxJob?.isActive == true) return
        inboxJob = accountTasks.scope.launch {
            while (true) {
                delay(15_000)
                boundedRequest { repository.connections() }.onSuccess { response -> _state.update { it.copy(connections = response.items) } }
                boundedRequest { repository.matches() }.onSuccess { response -> _state.update { it.copy(matches = response.items) } }
            }
        }
    }

    private fun <T> work(message: String, request: suspend () -> Result<T>, onSuccess: (T) -> Unit) {
        if (_state.value.isWorking) {
            return
        }
        _state.update { it.copy(isWorking = true, error = null, message = null) }
        accountTasks.scope.launch {
            boundedRequest(request).fold(
                onSuccess = { value -> onSuccess(value); _state.update { it.copy(isWorking = false, message = message) } },
                onFailure = { fail(it, loading = false) },
            )
        }
    }

    private fun fail(error: Throwable, loading: Boolean = false) {
        _state.update { it.copy(isLoading = loading, isWorking = false, error = PlayMatchUiPolicy.safeError(error)) }
    }

    private suspend fun <T> boundedRequest(block: suspend () -> Result<T>): Result<T> = try {
        withTimeoutOrNull(8_000L) { block() }
            ?: Result.failure(IllegalStateException("El servidor tardó demasiado en responder."))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    override fun onCleared() {
        accountTasks.close()
        pollingJob?.cancel()
        inboxJob?.cancel()
        catalogSearchJob?.cancel()
        homeJob?.cancel()
        super.onCleared()
    }
}

private fun PlayMatchProfile.asUpdateRequest(photos: List<String> = this.photos) = UpdatePlayMatchProfileRequest(
    expectedVersion = version,
    gamerDisplayName = displayName,
    gamerAvatarUrl = avatarUrl,
    gamerAvatarMediaType = avatarMediaType,
    gamerBio = gamerBio,
    regionCode = regionCode,
    languages = languages,
    playStyle = playStyle,
    voicePreference = voicePreference,
    discoveryLevel = discoveryLevel,
    discoverable = discoverable,
    adultConfirmed = adultConfirmed == true,
    gameIdVisibility = gameIdVisibility ?: "connections",
    photosVisibility = photosVisibility ?: "connections",
    availabilityVisibility = availabilityVisibility ?: "connections",
    photos = photos.distinct().take(4),
    timezone = timezone,
)
