package conectate.com.presentation.live

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.LiveRepository
import conectate.com.data.repository.LiveChannelPointsRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.normalizeLiveSessionId
import conectate.com.data.supabase.AuthService
import conectate.com.data.model.LiveRole
import conectate.com.data.livekit.LiveKitRoomBridge
import conectate.com.data.model.GiftReceipt
import conectate.com.data.model.LivePointRedemption
import conectate.com.data.model.LivePointReward
import conectate.com.data.model.LivePointsSettings
import conectate.com.data.points.LiveChannelPointsApiException
import conectate.com.data.webrtc.LiveCameraFilter
import conectate.com.presentation.common.toUserFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LiveHostPreparationState {
    data object Idle : LiveHostPreparationState
    data class Loading(val liveId: String) : LiveHostPreparationState
    data class Ready(val requirements: LiveHostRuntimeRequirements) : LiveHostPreparationState
    data class Failed(val message: String) : LiveHostPreparationState
}

private data class LiveConnectionRequest(
    val liveId: String,
    val role: LiveRole
)

data class LiveChannelPointsUiState(
    val available: Boolean? = null,
    val sessionActive: Boolean = false,
    val foreground: Boolean = true,
    val balance: Int = 0,
    val settings: LivePointsSettings? = null,
    val rewards: List<LivePointReward> = emptyList(),
    val redemptions: List<LivePointRedemption> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class LiveSessionViewModel @Inject constructor(
    private val liveRepository: LiveRepository,
    private val liveChannelPointsRepository: LiveChannelPointsRepository,
    private val authService: AuthService,
    private val userRepository: UserRepository
) : ViewModel() {

    val activeSession = liveRepository.activeSession
    val comments = liveRepository.comments
    val reactions = liveRepository.reactions
    val reactionCount = liveRepository.reactionCount
    val giftEvents = liveRepository.giftEvents
    val viewerCount = liveRepository.viewerCount
    val liveViewers = liveRepository.liveViewers
    val collaborationCapabilities = liveRepository.collaborationCapabilities
    val guestInvitations = liveRepository.guestInvitations
    val activeBattle = liveRepository.activeBattle
    val liveSupporters = liveRepository.liveSupporters
    val moderationState = liveRepository.moderationState
    val isMicEnabled = liveRepository.isMicEnabled
    val isMediaReady = liveRepository.isMediaReady
    val isPaused = liveRepository.isPaused
    val isVideoEnabled = liveRepository.isVideoEnabled
    val isMediaTransitioning = liveRepository.isMediaTransitioning
    val currentSource = liveRepository.currentSource
    val cameraFilter = liveRepository.cameraFilter
    val cameraFilterPerformance = liveRepository.cameraFilterPerformance
    val eglBaseContext = liveRepository.eglBaseContext
    val localVideoTrack = liveRepository.localVideoTrack
    val remoteVideoTracks = liveRepository.remoteVideoTracks
    val liveKitState: StateFlow<LiveKitRoomBridge.State> = liveRepository.liveKitState
    val liveKitRemoteVideos = liveRepository.liveKitRemoteVideos

    private val _channelPoints = MutableStateFlow(LiveChannelPointsUiState())
    val channelPoints: StateFlow<LiveChannelPointsUiState> = _channelPoints.asStateFlow()

    private val _isFollowingHost = MutableStateFlow<Boolean?>(null)
    val isFollowingHost: StateFlow<Boolean?> = _isFollowingHost.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    private val _hostPreparation = MutableStateFlow<LiveHostPreparationState>(LiveHostPreparationState.Idle)
    val hostPreparation: StateFlow<LiveHostPreparationState> = _hostPreparation.asStateFlow()

    private val _connectionRequest = MutableStateFlow<LiveConnectionRequest?>(null)
    private var collaborationPollingJob: Job? = null
    private var collaborationPollingLiveId: String? = null
    private var channelPointsJob: Job? = null
    private var channelPointsLiveId: String? = null

    init {
        viewModelScope.launch {
            liveRepository.runtimeErrors.collect { message ->
                _uiError.value = message
            }
        }
    }

    fun getCurrentUserId(): String? = authService.getCurrentUserId()

    fun startChannelPoints(liveId: String) {
        val normalizedId = normalizeLiveSessionId(liveId) ?: return
        if (channelPointsJob?.isActive == true && channelPointsLiveId == normalizedId) return
        channelPointsJob?.cancel()
        channelPointsLiveId = normalizedId
        channelPointsJob = viewModelScope.launch(Dispatchers.IO) {
            refreshChannelPointsInternal(normalizedId, includeRedemptions = false)
            val session = liveChannelPointsRepository.startSession(normalizedId).getOrElse { error ->
                handleChannelPointsError(error)
                return@launch
            }
            _channelPoints.value = _channelPoints.value.copy(
                available = true,
                sessionActive = true,
                message = "Gana ${session.pointsPerInterval} puntos cada ${session.intervalSeconds / 60} min mientras miras.",
            )
            while (true) {
                delay(30_000L)
                val foreground = _channelPoints.value.foreground
                liveChannelPointsRepository.heartbeat(
                    liveId = normalizedId,
                    sessionToken = session.sessionToken,
                    visible = foreground,
                    foreground = foreground,
                ).onSuccess { heartbeat ->
                    val nextBalance = heartbeat.balance ?: _channelPoints.value.balance
                    _channelPoints.value = _channelPoints.value.copy(
                        balance = nextBalance,
                        message = when {
                            heartbeat.awardedPoints > 0 -> "+${heartbeat.awardedPoints} puntos por mirar"
                            heartbeat.dailyCapReached -> "Alcanzaste el límite diario de puntos"
                            else -> _channelPoints.value.message
                        },
                    )
                }.onFailure(::handleChannelPointsError)
            }
        }
    }

    fun setChannelPointsForeground(foreground: Boolean) {
        _channelPoints.value = _channelPoints.value.copy(foreground = foreground)
    }

    fun stopChannelPoints() {
        channelPointsJob?.cancel()
        channelPointsJob = null
        channelPointsLiveId = null
        _channelPoints.value = _channelPoints.value.copy(sessionActive = false)
    }

    fun refreshChannelPoints(liveId: String, includeRedemptions: Boolean = true) {
        val normalizedId = normalizeLiveSessionId(liveId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            refreshChannelPointsInternal(normalizedId, includeRedemptions)
        }
    }

    private suspend fun refreshChannelPointsInternal(liveId: String, includeRedemptions: Boolean) {
        _channelPoints.value = _channelPoints.value.copy(busy = true)
        val balance = liveChannelPointsRepository.balance(liveId)
        if (balance.isFailure) {
            handleChannelPointsError(balance.exceptionOrNull())
            _channelPoints.value = _channelPoints.value.copy(busy = false)
            return
        }
        val rewards = liveChannelPointsRepository.rewards(liveId)
        if (rewards.isFailure) {
            handleChannelPointsError(rewards.exceptionOrNull())
            _channelPoints.value = _channelPoints.value.copy(busy = false)
            return
        }
        val redemptions = if (includeRedemptions) {
            liveChannelPointsRepository.redemptions(liveId).getOrElse { emptyListResponse() }.redemptions
        } else {
            _channelPoints.value.redemptions
        }
        val response = balance.getOrThrow()
        _channelPoints.value = _channelPoints.value.copy(
            available = true,
            balance = response.balance.balance,
            settings = response.settings,
            rewards = rewards.getOrThrow().rewards,
            redemptions = redemptions,
            busy = false,
        )
    }

    private fun emptyListResponse() = conectate.com.data.model.LivePointRedemptionsResponse()

    fun redeemChannelPointReward(liveId: String, rewardId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _channelPoints.value = _channelPoints.value.copy(busy = true)
            liveChannelPointsRepository.redeem(liveId, rewardId)
                .onSuccess { response ->
                    _channelPoints.value = _channelPoints.value.copy(
                        balance = response.balance ?: _channelPoints.value.balance,
                        busy = false,
                        message = "Recompensa solicitada al creador",
                    )
                    refreshChannelPointsInternal(liveId, includeRedemptions = false)
                }
                .onFailure { error ->
                    handleChannelPointsError(error)
                    _channelPoints.value = _channelPoints.value.copy(busy = false)
                }
        }
    }

    fun updateChannelPointSettings(liveId: String, enabled: Boolean, interval: Int, points: Int, cap: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _channelPoints.value = _channelPoints.value.copy(busy = true)
            liveChannelPointsRepository.updateSettings(liveId, enabled, interval, points, cap)
                .onSuccess {
                    _channelPoints.value = _channelPoints.value.copy(settings = it.settings, busy = false, message = "Puntos actualizados")
                }
                .onFailure { handleChannelPointsError(it); _channelPoints.value = _channelPoints.value.copy(busy = false) }
        }
    }

    fun createChannelPointReward(liveId: String, title: String, description: String, cost: Int, stock: Int?, cooldown: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            _channelPoints.value = _channelPoints.value.copy(busy = true)
            liveChannelPointsRepository.createReward(liveId, title, description, cost, stock, cooldown)
                .onSuccess { refreshChannelPointsInternal(liveId, includeRedemptions = true) }
                .onFailure { handleChannelPointsError(it); _channelPoints.value = _channelPoints.value.copy(busy = false) }
        }
    }

    fun decideChannelPointRedemption(liveId: String, redemptionId: String, status: String) {
        viewModelScope.launch(Dispatchers.IO) {
            liveChannelPointsRepository.updateRedemption(liveId, redemptionId, status)
                .onSuccess { refreshChannelPointsInternal(liveId, includeRedemptions = true) }
                .onFailure(::handleChannelPointsError)
        }
    }

    private fun handleChannelPointsError(error: Throwable?) {
        val code = (error as? LiveChannelPointsApiException)?.code ?: error?.message.orEmpty()
        if (code == "LIVE_CHANNEL_POINTS_DISABLED" || code == "LIVE_POINTS_DISABLED_FOR_LIVE") {
            _channelPoints.value = _channelPoints.value.copy(available = false, sessionActive = false, message = null)
            return
        }
        _channelPoints.value = _channelPoints.value.copy(
            message = when (code) {
                "LIVE_POINT_BALANCE_INSUFFICIENT" -> "No tienes puntos suficientes"
                "LIVE_POINT_REWARD_COOLDOWN" -> "Esta recompensa todavía está en espera"
                "LIVE_POINT_REWARD_UNAVAILABLE" -> "La recompensa ya no está disponible"
                "LIVE_POINTS_NOT_CONFIGURED" -> "Los puntos aún no están configurados en el servidor"
                else -> "No se pudieron actualizar los puntos del canal"
            },
        )
    }

    fun startCollaborationPolling(liveId: String) {
        val normalizedId = normalizeLiveSessionId(liveId) ?: return
        if (collaborationPollingJob?.isActive == true && collaborationPollingLiveId == normalizedId) return
        collaborationPollingJob?.cancel()
        collaborationPollingLiveId = normalizedId
        collaborationPollingJob = viewModelScope.launch(Dispatchers.IO) {
            liveRepository.getLiveCollaborationCapabilities()
            while (true) {
                liveRepository.refreshGuestInvitations(normalizedId)
                liveRepository.refreshActiveBattle(normalizedId)
                liveRepository.refreshLiveSupporters(normalizedId)
                delay(4_000L)
            }
        }
    }

    fun inviteGuest(liveId: String, userId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.inviteLiveGuest(liveId, userId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo enviar la invitación.") }
        }
    }

    fun requestToJoin(liveId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.requestToJoinLive(liveId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo enviar la solicitud para unirte.") }
        }
    }

    fun setGuestRequestsAllowed(liveId: String, allowed: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.setGuestRequestsAllowed(liveId, allowed)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo actualizar el permiso de invitados.") }
        }
    }

    fun respondToGuestInvitation(
        liveId: String,
        invitationId: String,
        accept: Boolean,
        publishCamera: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.respondToGuestInvitation(liveId, invitationId, accept)
                .onSuccess { invitation ->
                    val currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
                    if (accept && invitation.inviteeUserId.equals(currentUserId, ignoreCase = true)) {
                        liveRepository.connectLiveKit(
                            liveId = liveId,
                            role = "guest",
                            publishAudio = true,
                            publishCamera = publishCamera
                        ).onFailure { error ->
                            _uiError.value = error.toUserFacingMessage("La invitación se aceptó, pero no se pudo abrir el audio del invitado.")
                        }
                    }
                }
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo responder la invitación.") }
        }
    }

    fun startBattle(liveId: String, opponentUserId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.startBattle(liveId, opponentUserId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo iniciar la batalla.") }
        }
    }

    fun endBattle(liveId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.endBattle(liveId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo finalizar la batalla.") }
        }
    }

    fun removeGuest(liveId: String, guestUserId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.removeLiveGuest(liveId, guestUserId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo retirar al invitado.") }
        }
    }

    fun muteGuest(liveId: String, guestUserId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.muteLiveGuest(liveId, guestUserId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo silenciar al invitado.") }
        }
    }

    fun prepareHost(liveId: String) {
        val normalizedId = normalizeLiveSessionId(liveId)
        if (normalizedId == null) {
            _hostPreparation.value = LiveHostPreparationState.Failed("El identificador del live no es válido.")
            return
        }

        when (val current = _hostPreparation.value) {
            is LiveHostPreparationState.Loading -> if (current.liveId == normalizedId) return
            is LiveHostPreparationState.Ready -> if (current.requirements.liveId == normalizedId) return
            else -> Unit
        }

        _hostPreparation.value = LiveHostPreparationState.Loading(normalizedId)
        viewModelScope.launch(Dispatchers.IO) {
            val stream = liveRepository.getLiveStreamById(normalizedId).getOrElse { error ->
                _hostPreparation.value = LiveHostPreparationState.Failed(
                    error.toUserFacingMessage("No se pudo preparar el live.")
                )
                return@launch
            }
            val currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
            if (currentUserId.isBlank() || !stream.hostUserId.equals(currentUserId, ignoreCase = true)) {
                _hostPreparation.value = LiveHostPreparationState.Failed("No eres el anfitrión de este live.")
                return@launch
            }

            _hostPreparation.value = LiveHostPreparationState.Ready(
                LiveHostRuntimeRequirements(
                    liveId = normalizedId,
                    source = liveSourceFromWire(stream.source),
                    micEnabled = liveRepository.isMicEnabled.value,
                    videoEnabled = liveSourceFromWire(stream.source) != conectate.com.data.model.LiveSource.AUDIO
                )
            )
        }
    }

    fun host(liveId: String) {
        startSession(liveId = liveId, role = LiveRole.HOST) { id ->
            liveRepository.hostLive(id)
        }
    }

    fun joinAsViewer(liveId: String) {
        startSession(liveId = liveId, role = LiveRole.VIEWER) { id ->
            liveRepository.joinLiveAsViewer(id)
        }
    }

    private fun startSession(
        liveId: String,
        role: LiveRole,
        start: suspend (String) -> Result<Unit>
    ) {
        val normalizedId = normalizeLiveSessionId(liveId)
        if (normalizedId == null) {
            _uiError.value = "El identificador del live no es válido."
            return
        }

        val existingSession = activeSession.value
        if (
            existingSession?.role == role &&
            existingSession.stream.id.equals(normalizedId, ignoreCase = true)
        ) {
            return
        }

        val request = LiveConnectionRequest(liveId = normalizedId, role = role)
        if (_connectionRequest.value == request) return
        if (_connectionRequest.value != null) {
            _uiError.value = "Ya se está preparando otro live."
            return
        }

        _connectionRequest.value = request
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = start(normalizedId)
                if (result.isFailure) {
                    val fallback = if (role == LiveRole.HOST) {
                        "No se pudo iniciar el live."
                    } else {
                        "No se pudo abrir el live."
                    }
                    _uiError.value = result.exceptionOrNull().toUserFacingMessage(fallback)
                }
            } finally {
                if (_connectionRequest.value == request) {
                    _connectionRequest.value = null
                }
            }
        }
    }

    fun endLive() {
        liveRepository.endLiveAsync()
    }

    suspend fun connectLiveKit(
        liveId: String,
        role: String,
        publishAudio: Boolean,
        publishCamera: Boolean
    ): Result<Unit> = liveRepository.connectLiveKit(
        liveId = liveId,
        role = role,
        publishAudio = publishAudio,
        publishCamera = publishCamera
    )

    fun disconnectLiveKit() {
        liveRepository.disconnectLiveKit()
    }

    fun attachLiveKitRenderer(
        video: LiveKitRoomBridge.RemoteVideo,
        renderer: io.livekit.android.renderer.TextureViewRenderer
    ): Boolean = liveRepository.attachLiveKitRenderer(video, renderer)

    fun detachLiveKitRenderer(
        video: LiveKitRoomBridge.RemoteVideo,
        renderer: io.livekit.android.renderer.TextureViewRenderer
    ) = liveRepository.detachLiveKitRenderer(video, renderer)

    suspend fun endLiveAndAwait(): Result<Unit> = liveRepository.endLive()

    fun toggleMic(enabled: Boolean) {
        liveRepository.toggleMic(enabled)
    }

    fun pauseLive() {
        liveRepository.pauseLive()
    }

    fun resumeLive(screenIntent: Intent? = null): Boolean {
        return liveRepository.resumeLive(screenIntent)
    }

    fun applyHostConfiguration(
        source: conectate.com.data.model.LiveSource,
        micEnabled: Boolean,
        videoEnabled: Boolean,
        projectionIntent: Intent? = null
    ): Boolean {
        return liveRepository.updateHostConfiguration(
            source = source,
            micEnabled = micEnabled,
            videoEnabled = videoEnabled,
            projectionIntent = projectionIntent
        )
    }

    fun canReuseScreenShareIntent(): Boolean = liveRepository.canReuseScreenShareIntent()

    fun startCamera(): Boolean {
        return liveRepository.startCamera()
    }

    fun setCameraFilter(filter: LiveCameraFilter) {
        liveRepository.setCameraFilter(filter)
    }

    fun switchCamera(): Boolean = liveRepository.switchCamera()

    fun startScreenShare(intent: Intent): Boolean {
        return liveRepository.startScreenShare(intent)
    }

    fun stopVideo() {
        liveRepository.stopVideo()
    }

    fun leaveLive() {
        stopCollaborationPolling()
        liveRepository.endLiveAsync()
    }

    suspend fun leaveLiveAndAwait(): Result<Unit> {
        stopCollaborationPolling()
        return liveRepository.endLive()
    }

    private fun stopCollaborationPolling() {
        collaborationPollingJob?.cancel()
        collaborationPollingJob = null
        collaborationPollingLiveId = null
    }

    fun sendComment(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = liveRepository.sendComment(text)
            if (result.isFailure) {
                _uiError.value = result.exceptionOrNull().toUserFacingMessage("No se pudo enviar el comentario.")
            }
        }
    }

    fun sendReaction() {
        liveRepository.sendReaction()
    }

    fun announceCommittedGift(receipt: GiftReceipt) {
        liveRepository.announceCommittedGift(receipt)
    }

    fun updateModerationSettings(chatPaused: Boolean, blockedWords: List<String>) {
        liveRepository.updateLiveModerationSettings(
            chatPaused = chatPaused,
            blockedWords = blockedWords
        )
    }

    fun updateModerationPause(chatPaused: Boolean) {
        liveRepository.updateLiveModerationSettings(chatPaused = chatPaused)
    }

    fun setModerator(userId: String, enabled: Boolean) {
        liveRepository.setLiveModerator(userId, enabled)
    }

    fun moderateViewer(userId: String, ban: Boolean) {
        liveRepository.moderateLiveViewer(userId, ban)
    }

    fun markReplayMoment(title: String, onSaved: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.addLiveChapter(title)
                .onSuccess { onSaved() }
                .onFailure {
                    _uiError.value = it.toUserFacingMessage("No se pudo guardar el momento.")
                }
        }
    }

    fun clearError() {
        _uiError.value = null
    }

    fun refreshFollowing(hostUserId: String) {
        val id = hostUserId.trim()
        if (id.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val isFollowing = userRepository.checkIsFollowing(id).getOrNull() ?: false
            _isFollowingHost.value = isFollowing
        }
    }

    fun toggleFollow(hostUserId: String) {
        val id = hostUserId.trim()
        if (id.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val currentlyFollowing = _isFollowingHost.value
                ?: (userRepository.checkIsFollowing(id).getOrNull() ?: false)

            val result = if (currentlyFollowing) {
                userRepository.unfollowUser(id)
            } else {
                userRepository.followUser(id)
            }

            if (result.isSuccess) {
                _isFollowingHost.value = !currentlyFollowing
            }
        }
    }
}
