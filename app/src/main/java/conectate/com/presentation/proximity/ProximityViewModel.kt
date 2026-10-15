package conectate.com.presentation.proximity

import conectate.com.core.media.readMediaBytesWithLimit

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.AnonymousChatMessage
import conectate.com.data.localfallback.AnonymousChatSession
import conectate.com.data.localfallback.AnonymousMatchResult
import conectate.com.data.localfallback.AnonymousBottle
import conectate.com.data.localfallback.AnonymousBottleResult
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.NearbyProfile
import conectate.com.data.repository.MessageRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import conectate.com.presentation.common.toUserFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltViewModel
class ProximityViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val authService: AuthService,
    private val apiClient: LocalFallbackApiClient,
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

    private val _outgoingBottle = MutableStateFlow<AnonymousBottle?>(null)
    val outgoingBottle: StateFlow<AnonymousBottle?> = _outgoingBottle.asStateFlow()

    private val _incomingBottle = MutableStateFlow<AnonymousBottle?>(null)
    val incomingBottle: StateFlow<AnonymousBottle?> = _incomingBottle.asStateFlow()

    private val _bottleInboxStatus = MutableStateFlow(BottleInboxStatus.INACTIVE)
    val bottleInboxStatus: StateFlow<BottleInboxStatus> = _bottleInboxStatus.asStateFlow()

    private val _normalChatPeerBySession = MutableStateFlow<Map<String, String>>(emptyMap())
    val normalChatPeerBySession: StateFlow<Map<String, String>> = _normalChatPeerBySession.asStateFlow()

    private val ownerAccountId = authService.getCurrentUserId()
    private var bottleInboxVisible = false
    private var anonymousSelectionGeneration = 0L
    private var anonymousMediaSendJob: Job? = null
    private var matchPollingJob: Job? = null
    private var messagePollingJob: Job? = null
    private var bottlePollingJob: Job? = null
    private var bottleDeactivationJob: Job? = null
    private val declinedBottleIds = linkedSetOf<String>()
    private val promotingSessionIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            authService.currentSession.map { authService.getCurrentUserId() }.distinctUntilChanged().collect { account ->
                if (account != ownerAccountId) {
                    cancelPolling()
                    bottleInboxVisible = false
                    _nearbyEnabled.value = false
                    _nearbyProfiles.value = emptyList()
                    _anonymousSession.value = null
                    _anonymousSessions.value = emptyList()
                    _anonymousMessages.value = emptyList()
                    _incomingBottle.value = null
                    _outgoingBottle.value = null
                    _bottleInboxStatus.value = BottleInboxStatus.INACTIVE
                    _normalChatPeerBySession.value = emptyMap()
                    _errorMessage.value = "La cuenta cambió. Vuelve a abrir Conectar."
                    // Cancel old account fetches and this observer: the navigation
                    // creates a fresh ViewModel for the new account.
                    viewModelScope.coroutineContext.cancelChildren()
                }
            }
        }
        restoreNearbyStatus()
        refreshAnonymousSessions(showErrors = false)
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
        invalidateAnonymousSend()
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

    fun startAnonymousMatch(gender: String, lookingFor: String) {
        if (_randomStatus.value == "waiting") return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val token = accessTokenOrReport() ?: return@launch finishLoading()
            // Anonymous matching is global and does not require location
            // permission. Nearby radar keeps its own presence flow; random
            // chat only needs the authenticated queue request below.
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

    fun setBottleInboxVisible(visible: Boolean) {
        bottleInboxVisible = visible
        if (visible) activateBottleInbox() else deactivateBottleInbox()
    }

    fun activateBottleInbox() {
        if (!shouldReceiveBottle(true, bottleInboxVisible, authService.getCurrentUserId() == ownerAccountId,
                _anonymousSession.value != null)) return
        if (bottlePollingJob?.isActive == true) return
        _bottleInboxStatus.value = BottleInboxStatus.CONNECTING
        // The redesigned flow uses the queue only as recipient availability.
        // Stop legacy auto-match polling so it can never bypass acceptance.
        matchPollingJob?.cancel()
        matchPollingJob = null
        _randomStatus.value = "idle"
        val pendingDeactivation = bottleDeactivationJob
        bottlePollingJob = viewModelScope.launch {
            // A quick tab exit/re-entry must not let the older DELETE arrive
            // after the new availability POST and silently unregister us.
            pendingDeactivation?.join()
            if (bottleDeactivationJob === pendingDeactivation) bottleDeactivationJob = null
            while (isActive) {
                val token = accessTokenOrReport(showErrors = false)
                if (token != null) {
                    val availabilityResult = apiClient.refreshAnonymousBottleAvailability(token)
                    if (!isActive) return@launch
                    availabilityResult
                        .onSuccess { availability ->
                            val offered = availability.bottle
                            if (offered != null && declinedBottleIds.contains(offered.id)) {
                                val retry = apiClient.respondToAnonymousBottle(token, offered.id, accept = false)
                                if (retry.isSuccess || retry.exceptionOrNull()?.message == "BOTTLE_OFFER_NOT_FOUND") {
                                    declinedBottleIds.remove(offered.id)
                                }
                                _incomingBottle.value = null
                                _bottleInboxStatus.value = if (retry.isSuccess) {
                                    BottleInboxStatus.WAITING
                                } else {
                                    BottleInboxStatus.ERROR
                                }
                            } else {
                                _incomingBottle.value = offered
                                _bottleInboxStatus.value = if (offered == null) {
                                    BottleInboxStatus.WAITING
                                } else {
                                    BottleInboxStatus.RECEIVED
                                }
                            }
                        }
                        .onFailure { _bottleInboxStatus.value = BottleInboxStatus.ERROR }
                    val outgoingResult = apiClient.loadOutgoingAnonymousBottle(token)
                    if (!isActive) return@launch
                    outgoingResult.onSuccess(::applyBottleResult)
                } else {
                    _bottleInboxStatus.value = BottleInboxStatus.ERROR
                }
                delay(2_500)
            }
        }
    }

    fun deactivateBottleInbox() {
        bottlePollingJob?.cancel()
        bottlePollingJob = null
        _incomingBottle.value = null
        _bottleInboxStatus.value = BottleInboxStatus.INACTIVE
        if (bottleDeactivationJob?.isActive == true) return
        bottleDeactivationJob = viewModelScope.launch {
            val token = accessTokenOrReport(showErrors = false) ?: return@launch
            // The server removes availability and atomically sends any pending
            // offer onward, including one that arrived before this client poll.
            apiClient.disableAnonymousBottleAvailability(token)
        }
    }

    fun sendAnonymousBottle(content: String) {
        val cleanContent = content.trim().take(500)
        if (cleanContent.isBlank() || _outgoingBottle.value != null || _isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            val token = accessTokenOrReport() ?: return@launch finishLoading()
            apiClient.sendAnonymousBottle(
                accessToken = token,
                content = cleanContent,
            ).onSuccess(::applyBottleResult)
                .onFailure(::reportBottleFailure)
            activateBottleInbox()
            finishLoading()
        }
    }

    fun acceptIncomingBottle() {
        if (_isLoading.value) return
        val bottle = _incomingBottle.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val token = accessTokenOrReport() ?: return@launch finishLoading()
            apiClient.respondToAnonymousBottle(token, bottle.id, accept = true)
                .onSuccess { result ->
                    _incomingBottle.value = null
                    applyBottleResult(result)
                }
                .onFailure { error ->
                    if (error.message == "BOTTLE_OFFER_NOT_FOUND") _incomingBottle.value = null
                    reportBottleFailure(error)
                }
            finishLoading()
        }
    }

    fun declineIncomingBottle() {
        if (_isLoading.value) return
        val bottle = _incomingBottle.value ?: return
        declinedBottleIds += bottle.id
        _incomingBottle.value = null
        _bottleInboxStatus.value = BottleInboxStatus.WAITING
        viewModelScope.launch {
            val token = accessTokenOrReport() ?: return@launch
            val result = apiClient.respondToAnonymousBottle(token, bottle.id, accept = false)
            if (result.isSuccess || result.exceptionOrNull()?.message == "BOTTLE_OFFER_NOT_FOUND") {
                declinedBottleIds.remove(bottle.id)
            } else {
                reportBottleFailure(result.exceptionOrNull())
            }
        }
    }

    fun cancelOutgoingBottle() {
        val bottle = _outgoingBottle.value ?: return
        _outgoingBottle.value = null
        _randomStatus.value = "idle"
        viewModelScope.launch {
            val token = accessTokenOrReport() ?: return@launch
            apiClient.cancelAnonymousBottle(token, bottle.id)
                .onFailure(::reportBottleFailure)
        }
    }

    private fun captureAnonymousSendIdentity(): AnonymousSendIdentity? {
        val session = _anonymousSession.value ?: return null
        val accountId = authService.getCurrentUserId()?.takeIf { it == ownerAccountId } ?: return null
        if (session.status != "active") return null
        return AnonymousSendIdentity(accountId, session.id, anonymousSelectionGeneration, UUID.randomUUID().toString())
    }

    private fun isCurrentAnonymousSend(identity: AnonymousSendIdentity): Boolean =
        identity.isCurrent(authService.getCurrentUserId(), _anonymousSession.value?.id,
            _anonymousSession.value?.status, anonymousSelectionGeneration)

    private fun invalidateAnonymousSend() {
        anonymousSelectionGeneration++
        anonymousMediaSendJob?.cancel()
        anonymousMediaSendJob = null
        _isLoading.value = false
    }

    fun sendAnonymousMessage(content: String) {
        val cleanContent = content.trim().take(1000)
        val identity = captureAnonymousSendIdentity() ?: return
        if (cleanContent.isBlank()) return
        viewModelScope.launch { sendAnonymousTypedMessage(identity, cleanContent, "text", null) }
    }

    fun sendAnonymousSticker(mediaUrl: String?, fallbackText: String) {
        val session = _anonymousSession.value ?: return
        val identity = captureAnonymousSendIdentity() ?: return
        if (!anonymousMediaUnlocked(session.messageCount, "sticker")) {
            _errorMessage.value = "Los stickers se desbloquean después de 6 mensajes."
            return
        }
        viewModelScope.launch {
            sendAnonymousTypedMessage(identity, fallbackText.trim().take(200).ifBlank { "Sticker" }, "sticker", mediaUrl)
        }
    }

    fun sendAnonymousMedia(uri: Uri, contentType: String) {
        val session = _anonymousSession.value ?: return
        val identity = captureAnonymousSendIdentity() ?: return
        val normalizedType = contentType.takeIf { it == "audio" || it == "image" } ?: return
        if (!anonymousMediaUnlocked(session.messageCount, normalizedType)) {
            _errorMessage.value = if (normalizedType == "audio") "El audio se desbloquea después de 3 mensajes."
                else "Las fotos se desbloquean después de 9 mensajes."
            return
        }
        if (anonymousMediaSendJob?.isActive == true) return
        anonymousMediaSendJob = viewModelScope.launch {
            _isLoading.value = true
            var uploadedPath: String? = null
            var published = false
            var submissionAttempted = false
            try {
                val resolver = appContext.contentResolver
                val mimeType = resolver.getType(uri).orEmpty().ifBlank {
                    if (normalizedType == "audio") "audio/mpeg" else "image/jpeg"
                }
                val bytes = withContext(Dispatchers.IO) {
                    val coroutine = kotlinx.coroutines.currentCoroutineContext()
                    resolver.openInputStream(uri)?.use { input ->
                        input.readMediaBytesWithLimit(25L * 1024 * 1024) { coroutine.ensureActive() }
                    }
                } ?: error("No se pudo leer el archivo seleccionado.")
                if (!isCurrentAnonymousSend(identity)) return@launch
                val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }.orEmpty().trim()
                val extension = displayName.substringAfterLast('.', "")
                    .ifBlank { MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType).orEmpty() }.take(8)
                val objectPath = "anonymous/${identity.sessionId}/${UUID.randomUUID()}" +
                    if (extension.isBlank()) "" else ".$extension"
                val upload = storageService.uploadFile("chat-media", objectPath, bytes, mimeType, allowOverwrite = false).getOrThrow()
                val storedPath = upload.Key ?: objectPath
                uploadedPath = storedPath
                if (!isCurrentAnonymousSend(identity)) return@launch
                val publicUrl = storageService.getPublicUrl("chat-media", storedPath)
                if (publicUrl.isBlank()) error("No se pudo preparar la vista del archivo.")
                submissionAttempted = true
                published = sendAnonymousTypedMessage(identity,
                    if (normalizedType == "audio") "Audio" else "Foto", normalizedType, publicUrl)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isCurrentAnonymousSend(identity)) reportFailure(error)
            } finally {
                // If a request may already have reached server, do not delete a
                // possibly committed attachment. Server orphan TTL reconciles it.
                val orphanPath = uploadedPath
                if (!published && !submissionAttempted && orphanPath != null &&
                    authService.getCurrentUserId() == identity.accountId) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        kotlinx.coroutines.withTimeoutOrNull(5_000) {
                            storageService.deleteFile("chat-media", orphanPath)
                        }
                    }
                }
                if (identity.selectionGeneration == anonymousSelectionGeneration) _isLoading.value = false
            }
        }
    }

    private suspend fun sendAnonymousTypedMessage(
        identity: AnonymousSendIdentity,
        content: String,
        contentType: String,
        mediaUrl: String?,
    ): Boolean {
        if (!isCurrentAnonymousSend(identity)) return false
        val token = accessTokenOrReport() ?: return false
        if (!isCurrentAnonymousSend(identity)) return false
        val result = apiClient.sendAnonymousMessage(token, identity.sessionId, identity.clientMessageId,
            content, contentType, mediaUrl)
        (result.exceptionOrNull() as? kotlinx.coroutines.CancellationException)?.let { throw it }
        result.onSuccess { sent ->
            if (isCurrentAnonymousSend(identity)) {
                _anonymousMessages.value = (_anonymousMessages.value + sent)
                    .distinctBy { it.id.ifBlank { it.clientMessageId } }
            }
        }.onFailure { if (isCurrentAnonymousSend(identity)) reportFailure(it) }
        return result.isSuccess
    }

    fun selectAnonymousSession(sessionId: String) {
        invalidateAnonymousSend()
        val session = _anonymousSessions.value.firstOrNull { it.id == sessionId } ?: return
        deactivateBottleInbox()
        _anonymousSession.value = session
        _anonymousMessages.value = emptyList()
        startMessagePolling(session.id)
        maybePromoteToNormalChat(session)
    }

    fun showAnonymousSessionList() {
        invalidateAnonymousSend()
        messagePollingJob?.cancel()
        messagePollingJob = null
        _anonymousSession.value = null
        _anonymousMessages.value = emptyList()
        refreshAnonymousSessions(showErrors = false)
        activateBottleInbox()
    }

    fun endAnonymousSession() {
        invalidateAnonymousSend()
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
            activateBottleInbox()
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

    private fun applyBottleResult(result: AnonymousBottleResult) {
        _outgoingBottle.value = result.bottle?.takeIf { it.perspective == "sender" && it.status in setOf("searching", "offered") }
        _randomStatus.value = when (result.status) {
            "searching", "offered" -> result.status
            else -> "idle"
        }
        result.session?.let { session ->
            _outgoingBottle.value = null
            _incomingBottle.value = null
            updateAnonymousSession(session)
            _anonymousSession.value = session
            deactivateBottleInbox()
            startMessagePolling(session.id)
            refreshAnonymousSessions(showErrors = false)
        }
    }

    private fun reportBottleFailure(error: Throwable?) {
        _errorMessage.value = when (error?.message) {
            "BOTTLE_ALREADY_SEARCHING" -> "Ya tienes una botella buscando a alguien."
            "BOTTLE_OFFER_NOT_FOUND" -> "Esa botella ya siguió su viaje hacia otra persona."
            "BOTTLE_MESSAGE_REQUIRED" -> "Escribe un mensaje antes de lanzar la botella."
            "RANDOM_ACTIVE_SESSION_LIMIT" -> "Cierra algún chat anónimo antes de abrir uno nuevo."
            "RANDOM_ALREADY_CONNECTED" -> "Ya tienes una conversación anónima activa con esa persona."
            "NOT_FOUND" -> "El servidor activo todavía no tiene disponible Botellas anónimas. Actualiza Novvin y vuelve a intentarlo."
            else -> error.toUserFacingMessage("No se pudo actualizar la botella anónima.")
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
                val token = accessTokenOrReport(showErrors = false) ?: continue
                apiClient.loadAnonymousMatchStatus(token)
                    .onSuccess { applyMatchResult(it, selectMatched = true) }
            }
        }
    }

    private fun startMessagePolling(sessionId: String) {
        messagePollingJob?.cancel()
        messagePollingJob = viewModelScope.launch {
            while (isActive && _anonymousSession.value?.id == sessionId) {
                val token = accessTokenOrReport(showErrors = false)
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
        if (authService.getCurrentUserId() != ownerAccountId) return null
        val token = authService.ensureValidAccessToken().getOrElse {
            if (it is kotlinx.coroutines.CancellationException) throw it
            if (showErrors) reportFailure(it)
            null
        }
        return token.takeIf { authService.getCurrentUserId() == ownerAccountId }
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
        invalidateAnonymousSend()
        matchPollingJob?.cancel()
        messagePollingJob?.cancel()
        bottlePollingJob?.cancel()
        matchPollingJob = null
        messagePollingJob = null
        bottlePollingJob = null
    }

    override fun onCleared() {
        cancelPolling()
        super.onCleared()
    }
}
