package conectate.com.presentation.proximity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.AnonymousChatMessage
import conectate.com.data.localfallback.AnonymousChatSession
import conectate.com.data.localfallback.AnonymousMatchResult
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.NearbyProfile
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@HiltViewModel
class ProximityViewModel @Inject constructor(
    private val authService: AuthService,
    private val apiClient: LocalFallbackApiClient,
    private val userRepository: UserRepository,
) : ViewModel() {
    private val _nearbyProfiles = MutableStateFlow<List<NearbyProfile>>(emptyList())
    val nearbyProfiles: StateFlow<List<NearbyProfile>> = _nearbyProfiles.asStateFlow()

    private val _nearbyEnabled = MutableStateFlow(false)
    val nearbyEnabled: StateFlow<Boolean> = _nearbyEnabled.asStateFlow()

    private val _radiusKm = MutableStateFlow(10.0)
    val radiusKm: StateFlow<Double> = _radiusKm.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _randomStatus = MutableStateFlow("idle")
    val randomStatus: StateFlow<String> = _randomStatus.asStateFlow()

    private val _anonymousSession = MutableStateFlow<AnonymousChatSession?>(null)
    val anonymousSession: StateFlow<AnonymousChatSession?> = _anonymousSession.asStateFlow()

    private val _anonymousMessages = MutableStateFlow<List<AnonymousChatMessage>>(emptyList())
    val anonymousMessages: StateFlow<List<AnonymousChatMessage>> = _anonymousMessages.asStateFlow()

    private val _gender = MutableStateFlow("unspecified")
    val gender: StateFlow<String> = _gender.asStateFlow()

    private val _lookingFor = MutableStateFlow("any")
    val lookingFor: StateFlow<String> = _lookingFor.asStateFlow()

    private var matchPollingJob: Job? = null
    private var messagePollingJob: Job? = null

    init {
        loadProfileMatchingDefaults()
        refreshRandomStatus(showErrors = false)
    }

    private fun loadProfileMatchingDefaults() {
        viewModelScope.launch {
            userRepository.ensureCurrentProfile().onSuccess { user ->
                val profileGender = user.gender.takeIf { it in setOf("man", "woman") }
                    ?: "unspecified"
                _gender.value = profileGender
                _lookingFor.value = when (profileGender) {
                    "man" -> "woman"
                    "woman" -> "man"
                    else -> "any"
                }
            }
        }
    }

    fun setGender(gender: String) {
        val normalized = gender.takeIf { it in setOf("man", "woman", "unspecified") }
            ?: "unspecified"
        _gender.value = normalized
    }

    fun setLookingFor(lookingFor: String) {
        _lookingFor.value = lookingFor.takeIf { it in setOf("man", "woman", "any") }
            ?: "any"
    }

    fun setRadius(radiusKm: Double) {
        _radiusKm.value = radiusKm.coerceIn(1.0, 20.0)
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun enableNearby(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val token = accessTokenOrReport() ?: return@launch finishLoading()
            apiClient.updateProximityPresence(
                accessToken = token,
                latitude = latitude,
                longitude = longitude,
                radiusKm = _radiusKm.value,
                gender = "unspecified",
                lookingFor = "any",
                randomEnabled = false
            ).onSuccess {
                _nearbyEnabled.value = true
                loadNearbyInternal(token)
            }.onFailure(::reportFailure)
            finishLoading()
        }
    }

    fun refreshNearby() {
        viewModelScope.launch {
            _isLoading.value = true
            val token = accessTokenOrReport() ?: return@launch finishLoading()
            loadNearbyInternal(token)
            finishLoading()
        }
    }

    fun disableNearby() {
        viewModelScope.launch {
            val token = accessTokenOrReport() ?: return@launch
            apiClient.disableProximityPresence(token)
                .onSuccess {
                    cancelPolling()
                    _nearbyEnabled.value = false
                    _nearbyProfiles.value = emptyList()
                    _randomStatus.value = "idle"
                    _anonymousSession.value = null
                    _anonymousMessages.value = emptyList()
                }
                .onFailure(::reportFailure)
        }
    }

    fun startAnonymousMatch(
        latitude: Double,
        longitude: Double,
        gender: String,
        lookingFor: String
    ) {
        if (_randomStatus.value == "waiting") return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val token = accessTokenOrReport() ?: return@launch finishLoading()
            val presenceResult = apiClient.updateProximityPresence(
                accessToken = token,
                latitude = latitude,
                longitude = longitude,
                radiusKm = _radiusKm.value,
                gender = gender,
                lookingFor = lookingFor,
                randomEnabled = true
            )
            if (presenceResult.isFailure) {
                reportFailure(presenceResult.exceptionOrNull())
                return@launch finishLoading()
            }
            _nearbyEnabled.value = true
            apiClient.startAnonymousMatch(token, gender, lookingFor)
                .onSuccess { applyMatchResult(it) }
                .onFailure(::reportFailure)
            finishLoading()
        }
    }

    fun cancelAnonymousMatch() {
        matchPollingJob?.cancel()
        viewModelScope.launch {
            val token = accessTokenOrReport() ?: return@launch
            apiClient.cancelAnonymousMatch(token)
                .onSuccess { _randomStatus.value = "idle" }
                .onFailure(::reportFailure)
        }
    }

    fun sendAnonymousMessage(content: String) {
        val cleanContent = content.trim().take(1000)
        val session = _anonymousSession.value ?: return
        if (cleanContent.isBlank() || session.status != "active") return
        val clientMessageId = UUID.randomUUID().toString()
        viewModelScope.launch {
            val token = accessTokenOrReport() ?: return@launch
            apiClient.sendAnonymousMessage(token, session.id, clientMessageId, cleanContent)
                .onSuccess { sent ->
                    _anonymousMessages.value = (_anonymousMessages.value + sent)
                        .distinctBy { it.id.ifBlank { it.clientMessageId } }
                }
                .onFailure(::reportFailure)
        }
    }

    fun revealNextLevel() {
        val session = _anonymousSession.value ?: return
        if (session.ownRevealLevel >= 3) return
        viewModelScope.launch {
            val token = accessTokenOrReport() ?: return@launch
            apiClient.revealAnonymousProfile(token, session.id, session.ownRevealLevel + 1)
                .onSuccess { _anonymousSession.value = it }
                .onFailure(::reportFailure)
        }
    }

    fun endAnonymousSession() {
        val session = _anonymousSession.value ?: return
        cancelPolling()
        viewModelScope.launch {
            val token = accessTokenOrReport() ?: return@launch
            apiClient.endAnonymousSession(token, session.id)
                .onFailure(::reportFailure)
            _anonymousSession.value = null
            _anonymousMessages.value = emptyList()
            _randomStatus.value = "idle"
        }
    }

    fun refreshRandomStatus(showErrors: Boolean = true) {
        viewModelScope.launch {
            val token = accessTokenOrReport(showErrors) ?: return@launch
            apiClient.loadAnonymousMatchStatus(token)
                .onSuccess(::applyMatchResult)
                .onFailure { if (showErrors) reportFailure(it) }
        }
    }

    private suspend fun loadNearbyInternal(token: String) {
        apiClient.loadNearbyProfiles(token, _radiusKm.value)
            .onSuccess {
                _nearbyProfiles.value = it
                _nearbyEnabled.value = true
            }
            .onFailure(::reportFailure)
    }

    private fun applyMatchResult(result: AnonymousMatchResult) {
        _randomStatus.value = result.status
        if (result.session != null) {
            _anonymousSession.value = result.session
            matchPollingJob?.cancel()
            startMessagePolling(result.session.id)
        } else if (result.status == "waiting") {
            startMatchPolling()
        }
    }

    private fun startMatchPolling() {
        if (matchPollingJob?.isActive == true) return
        matchPollingJob = viewModelScope.launch {
            while (isActive && _randomStatus.value == "waiting") {
                delay(2_500)
                val token = authService.ensureValidAccessToken().getOrNull() ?: continue
                apiClient.loadAnonymousMatchStatus(token).onSuccess(::applyMatchResult)
            }
        }
    }

    private fun startMessagePolling(sessionId: String) {
        messagePollingJob?.cancel()
        messagePollingJob = viewModelScope.launch {
            while (isActive && _anonymousSession.value?.id == sessionId) {
                val token = authService.ensureValidAccessToken().getOrNull()
                if (token != null) {
                    apiClient.loadAnonymousMessages(token, sessionId)
                        .onSuccess { _anonymousMessages.value = it }
                    apiClient.loadAnonymousSession(token, sessionId)
                        .onSuccess { session ->
                            _anonymousSession.value = session
                            if (session.status != "active") {
                                _randomStatus.value = "idle"
                                return@launch
                            }
                        }
                }
                delay(1_800)
            }
        }
    }

    private suspend fun accessTokenOrReport(showErrors: Boolean = true): String? {
        return authService.ensureValidAccessToken().getOrElse {
            if (showErrors) reportFailure(it)
            null
        }
    }

    private fun reportFailure(error: Throwable?) {
        _errorMessage.value = error?.message ?: "No se pudo completar la operación."
    }

    private fun finishLoading() {
        _isLoading.value = false
    }

    private fun cancelPolling() {
        matchPollingJob?.cancel()
        messagePollingJob?.cancel()
        matchPollingJob = null
        messagePollingJob = null
    }

    override fun onCleared() {
        cancelPolling()
        super.onCleared()
    }
}
