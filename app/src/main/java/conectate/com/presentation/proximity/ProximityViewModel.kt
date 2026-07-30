package conectate.com.presentation.proximity

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.AnonymousChatMessage
import conectate.com.data.localfallback.AnonymousChatSession
import conectate.com.data.localfallback.AnonymousMatchResult
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.NearbyProfile
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import conectate.com.presentation.common.toUserFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltViewModel
class ProximityViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val authService: AuthService,
    private val apiClient: LocalFallbackApiClient,
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val storageService: StorageService,
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

    private val _anonymousSessions = MutableStateFlow<List<AnonymousChatSession>>(emptyList())
    val anonymousSessions: StateFlow<List<AnonymousChatSession>> = _anonymousSessions.asStateFlow()

    private val _anonymousMessages = MutableStateFlow<List<AnonymousChatMessage>>(emptyList())
    val anonymousMessages: StateFlow<List<AnonymousChatMessage>> = _anonymousMessages.asStateFlow()

    private val _normalChatPeerBySession = MutableStateFlow<Map<String, String>>(emptyMap())
    val normalChatPeerBySession: StateFlow<Map<String, String>> = _normalChatPeerBySession.asStateFlow()

    private val _gender = MutableStateFlow("unspecified")
    val gender: StateFlow<String> = _gender.asStateFlow()

    private val _lookingFor = MutableStateFlow("any")
    val lookingFor: StateFlow<String> = _lookingFor.asStateFlow()

    private var matchPollingJob: Job? = null
    private var messagePollingJob: Job? = null
    private val promotingSessionIds = mutableSetOf<String>()

    init {
        loadProfileMatchingDefaults()
        restoreNearbyStatus()
        refreshAnonymousSessions(showErrors = false)
        refreshRandomStatus(showErrors = false, selectMatched = false)
    }

    private fun restoreNearbyStatus() {
        viewModelScope.launch {
            val token = accessTokenOrReport(showErrors = false) ?: return@launch
            apiClient.loadNearbyProfiles(token, _radiusKm.value)
                .onSuccess { profiles ->
                    _nearbyProfiles.value = profiles
                    _nearbyEnabled.value = true
                }
        }
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
                .onSuccess { applyMatchResult(it, selectMatched = true) }
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
                .onSuccess {
                    updateAnonymousSession(it)
                    maybePromoteToNormalChat(it)
                }
                .onFailure(::reportFailure)
        }
    }

    fun sendAnonymousSticker(mediaUrl: String?, fallbackText: String) {
        val session = _anonymousSession.value ?: return
        if (session.friendshipPercent < 33) {
            _errorMessage.value = "Los stickers se desbloquean al alcanzar 33% de confianza."
            return
        }
        sendAnonymousTypedMessage(
            content = fallbackText.trim().take(200).ifBlank { "Sticker" },
            contentType = "sticker",
            mediaUrl = mediaUrl
        )
    }

    fun sendAnonymousMedia(uri: Uri, contentType: String) {
        val session = _anonymousSession.value ?: return
        val normalizedType = contentType.takeIf { it == "audio" || it == "image" } ?: return
        val required = if (normalizedType == "audio") 50 else 67
        if (session.friendshipPercent < required) {
            _errorMessage.value = "Esta opción se desbloquea al alcanzar $required% de confianza."
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val resolver = appContext.contentResolver
                val mimeType = resolver.getType(uri).orEmpty().ifBlank {
                    if (normalizedType == "audio") "audio/mpeg" else "image/jpeg"
                }
                val bytes = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error("No se pudo leer el archivo seleccionado.")
                if (bytes.size > 25 * 1024 * 1024) {
                    error("El archivo supera 25 MB. Elige uno más pequeño.")
                }
                val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    ?.trim()
                    .orEmpty()
                val extension = displayName.substringAfterLast('.', "")
                    .ifBlank { MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType).orEmpty() }
                    .take(8)
                val objectPath = buildString {
                    append("anonymous/")
                    append(session.id)
                    append('/')
                    append(UUID.randomUUID())
                    if (extension.isNotBlank()) append('.').append(extension)
                }
                val upload = storageService.uploadFile(
                    bucketName = "chat-media",
                    fileName = objectPath,
                    bytes = bytes,
                    contentType = mimeType,
                    allowOverwrite = false
                ).getOrThrow()
                val storedPath = upload.Key ?: objectPath
                val publicUrl = storageService.getPublicUrl("chat-media", storedPath)
                if (publicUrl.isBlank()) error("No se pudo preparar la vista del archivo.")
                sendAnonymousTypedMessage(
                    content = if (normalizedType == "audio") "Audio" else "Foto",
                    contentType = normalizedType,
                    mediaUrl = publicUrl
                )
            } catch (error: Exception) {
                reportFailure(error)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun sendAnonymousTypedMessage(
        content: String,
        contentType: String,
        mediaUrl: String?
    ) {
        val session = _anonymousSession.value ?: return
        if (session.status != "active") return
        val clientMessageId = UUID.randomUUID().toString()
        viewModelScope.launch {
            val token = accessTokenOrReport() ?: return@launch
            apiClient.sendAnonymousMessage(
                accessToken = token,
                sessionId = session.id,
                clientMessageId = clientMessageId,
                content = content,
                contentType = contentType,
                mediaUrl = mediaUrl
            ).onSuccess { sent ->
                _anonymousMessages.value = (_anonymousMessages.value + sent)
                    .distinctBy { it.id.ifBlank { it.clientMessageId } }
            }.onFailure(::reportFailure)
        }
    }

    fun selectAnonymousSession(sessionId: String) {
        val session = _anonymousSessions.value.firstOrNull { it.id == sessionId } ?: return
        _anonymousSession.value = session
        _anonymousMessages.value = emptyList()
        startMessagePolling(session.id)
        maybePromoteToNormalChat(session)
    }

    fun showAnonymousSessionList() {
        messagePollingJob?.cancel()
        messagePollingJob = null
        _anonymousSession.value = null
        _anonymousMessages.value = emptyList()
        refreshAnonymousSessions(showErrors = false)
    }

    fun endAnonymousSession() {
        val session = _anonymousSession.value ?: return
        messagePollingJob?.cancel()
        messagePollingJob = null
        viewModelScope.launch {
            val token = accessTokenOrReport() ?: return@launch
            apiClient.endAnonymousSession(token, session.id)
                .onFailure(::reportFailure)
            _anonymousSession.value = null
            _anonymousSessions.value = _anonymousSessions.value.filterNot { it.id == session.id }
            _anonymousMessages.value = emptyList()
            _normalChatPeerBySession.value = _normalChatPeerBySession.value - session.id
            refreshAnonymousSessions(showErrors = false)
        }
    }

    fun refreshRandomStatus(showErrors: Boolean = true, selectMatched: Boolean = false) {
        viewModelScope.launch {
            val token = accessTokenOrReport(showErrors) ?: return@launch
            apiClient.loadAnonymousMatchStatus(token)
                .onSuccess { applyMatchResult(it, selectMatched) }
                .onFailure { if (showErrors) reportFailure(it) }
        }
    }

    fun refreshAnonymousSessions(showErrors: Boolean = true) {
        viewModelScope.launch {
            val token = accessTokenOrReport(showErrors) ?: return@launch
            apiClient.loadAnonymousSessions(token)
                .onSuccess { sessions ->
                    _anonymousSessions.value = sessions
                    val selectedId = _anonymousSession.value?.id
                    if (selectedId != null) {
                        _anonymousSession.value = sessions.firstOrNull { it.id == selectedId }
                    }
                    sessions.forEach(::maybePromoteToNormalChat)
                }
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

    private fun applyMatchResult(result: AnonymousMatchResult, selectMatched: Boolean = true) {
        _randomStatus.value = result.status
        if (result.session != null) {
            updateAnonymousSession(result.session)
            if (selectMatched) {
                _anonymousSession.value = result.session
            }
            maybePromoteToNormalChat(result.session)
            matchPollingJob?.cancel()
            if (selectMatched) startMessagePolling(result.session.id)
        } else if (result.status == "waiting") {
            startMatchPolling()
        }
    }

    private fun updateAnonymousSession(session: AnonymousChatSession) {
        _anonymousSessions.value = (
            _anonymousSessions.value.filterNot { it.id == session.id } + session
        ).sortedByDescending { it.updatedAt.ifBlank { it.createdAt } }
        if (_anonymousSession.value?.id == session.id) {
            _anonymousSession.value = session
        }
    }

    private fun maybePromoteToNormalChat(session: AnonymousChatSession) {
        val peerId = session.peer.userId?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (session.ownRevealLevel < 3 || session.peerRevealLevel < 3) return
        if (_normalChatPeerBySession.value.containsKey(session.id)) return
        if (!promotingSessionIds.add(session.id)) return
        viewModelScope.launch {
            messageRepository.getOrCreateChat(peerId)
                .onSuccess {
                    _normalChatPeerBySession.value =
                        _normalChatPeerBySession.value + (session.id to peerId)
                }
                .onFailure(::reportFailure)
            promotingSessionIds.remove(session.id)
        }
    }

    private fun startMatchPolling() {
        if (matchPollingJob?.isActive == true) return
        matchPollingJob = viewModelScope.launch {
            while (isActive && _randomStatus.value == "waiting") {
                delay(1_000)
                val token = authService.ensureValidAccessToken().getOrNull() ?: continue
                apiClient.loadAnonymousMatchStatus(token)
                    .onSuccess { applyMatchResult(it, selectMatched = true) }
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
                            updateAnonymousSession(session)
                            maybePromoteToNormalChat(session)
                            if (session.status != "active") {
                                _anonymousSessions.value =
                                    _anonymousSessions.value.filterNot { it.id == session.id }
                                _anonymousSession.value = null
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
        _errorMessage.value = error.toUserFacingMessage(
            fallback = "No se pudo completar la operación. Inténtalo nuevamente."
        )
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
