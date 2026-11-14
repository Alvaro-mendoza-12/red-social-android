package conectate.com.presentation.live

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.LiveRepository
import conectate.com.data.repository.LiveChannelPointsRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.WalletRepository
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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

@HiltViewModel
class LiveSessionViewModel @Inject constructor(
    private val liveRepository: LiveRepository,
    private val liveChannelPointsRepository: LiveChannelPointsRepository,
    private val authService: AuthService,
    private val userRepository: UserRepository,
    private val walletRepository: WalletRepository
) : ViewModel() {

    val activeSession = liveRepository.activeSession
    val isFrontCamera = liveRepository.isFrontCamera
    val comments = liveRepository.comments
    val reactions = liveRepository.reactions
    val reactionCount = liveRepository.reactionCount
    val giftEvents = liveRepository.giftEvents
    val viewerCount = liveRepository.viewerCount
    val audienceStats = liveRepository.audienceStats
    val liveViewers = liveRepository.liveViewers
    val guestRequests = liveRepository.guestRequests
    val activeGuests = liveRepository.activeGuests
    val guestAccessState = liveRepository.guestAccessState
    val isGuestBroadcasting = liveRepository.isGuestBroadcasting
    val isGuestMicEnabled = liveRepository.isGuestMicEnabled
    val isGuestVideoEnabled = liveRepository.isGuestVideoEnabled
    val battleState = liveRepository.battleState
    val availableGifts = walletRepository.availableGifts
    val coinBalance = walletRepository.coinBalance
    val collaborationCapabilities = liveRepository.collaborationCapabilities
    val guestInvitations = liveRepository.guestInvitations
    val activeBattle = liveRepository.activeBattle
    val battleCandidates = liveRepository.battleCandidates
    val liveSupporters = liveRepository.liveSupporters
    val moderationState = liveRepository.moderationState
    val isMicEnabled = liveRepository.isMicEnabled
    val isMediaReady = liveRepository.isMediaReady
    val isPaused = liveRepository.isPaused
    val isVideoEnabled = liveRepository.isVideoEnabled
    val isMediaTransitioning = liveRepository.isMediaTransitioning
    val currentSource = liveRepository.currentSource
    val cameraFilter = liveRepository.cameraFilter
    val creatorLayoutStyle = liveRepository.creatorLayoutStyle
    val cameraFilterPerformance = liveRepository.cameraFilterPerformance
    val eglBaseContext = liveRepository.eglBaseContext
    val localVideoTrack = liveRepository.localVideoTrack
    val remoteVideoTracks = liveRepository.remoteVideoTracks
    val liveKitState: StateFlow<LiveKitRoomBridge.State> = liveRepository.liveKitState
    val liveKitRemoteVideos = liveRepository.liveKitRemoteVideos
    val liveKitLocalPreview = liveRepository.liveKitLocalPreview
    val liveKitRemoteParticipants = liveRepository.liveKitRemoteParticipants
    val liveMusic = liveRepository.liveMusic

    private val pointsController = LiveChannelPointsController(
        liveChannelPointsRepository, viewModelScope, authService::captureAccountLease, authService::commitForAccountLease,
        accountVisitChanges = authService.accountVisitChanges,
    )
    val channelPoints = pointsController.state

    private val _isFollowingHost = MutableStateFlow<Boolean?>(null)
    val isFollowingHost: StateFlow<Boolean?> = _isFollowingHost.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    private val _hostPreparation = MutableStateFlow<LiveHostPreparationState>(LiveHostPreparationState.Idle)
    val hostPreparation: StateFlow<LiveHostPreparationState> = _hostPreparation.asStateFlow()

    private val _connectionRequest = MutableStateFlow<LiveConnectionRequest?>(null)
    private var hostPreparationJob: Job? = null
    private var collaborationPollingJob: Job? = null
    private var collaborationPollingLiveId: String? = null

    init {
        viewModelScope.launch {
            liveRepository.runtimeErrors.collect { message ->
                _uiError.value = message
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            walletRepository.fetchAvailableGifts()
            walletRepository.fetchBalance()
        }
    }

    fun getCurrentUserId(): String? = authService.getCurrentUserId()

    internal val accountVisitGeneration: StateFlow<Long> get() = authService.accountVisitChanges

    internal fun captureToolVisit(liveId: String): LiveToolAccountVisit = captureLiveToolAccountVisit(
        liveId, authService::captureAccountLease, { accountVisitGeneration.value }, authService::commitForAccountLease,
    )

    internal fun channelPointsFor(visit: LiveToolAccountVisit): LiveChannelPointsUiState =
        pointsController.stateFor(visit.owner, visit.key.liveId)

    internal fun stopChannelPoints(visit: LiveToolAccountVisit) =
        pointsController.stopChannelPoints(visit.owner, visit.key.liveId)

    fun startChannelPoints(liveId: String) = pointsController.startChannelPoints(liveId)
    fun setChannelPointsForeground(foreground: Boolean) = pointsController.setChannelPointsForeground(foreground)
    fun stopChannelPoints() = pointsController.stopChannelPoints()
    fun refreshChannelPoints(liveId: String, includeRedemptions: Boolean = true) =
        pointsController.refreshChannelPoints(liveId, includeRedemptions)
    fun redeemChannelPointReward(liveId: String, rewardId: String) = pointsController.redeemChannelPointReward(liveId, rewardId)
    fun updateChannelPointSettings(liveId: String, enabled: Boolean, interval: Int, points: Int, cap: Int) =
        pointsController.updateChannelPointSettings(liveId, enabled, interval, points, cap)
    fun createChannelPointReward(liveId: String, title: String, description: String, cost: Int, stock: Int?, cooldown: Int) =
        pointsController.createChannelPointReward(liveId, title, description, cost, stock, cooldown)
    fun decideChannelPointRedemption(liveId: String, redemptionId: String, status: String) =
        pointsController.decideChannelPointRedemption(liveId, redemptionId, status)

    fun startCollaborationPolling(liveId: String) {
        val normalizedId = normalizeLiveSessionId(liveId) ?: return
        if (collaborationPollingJob?.isActive == true && collaborationPollingLiveId == normalizedId) return
        val previousPolling = collaborationPollingJob
        previousPolling?.cancel()
        collaborationPollingLiveId = normalizedId
        collaborationPollingJob = viewModelScope.launch(Dispatchers.IO) {
            previousPolling?.join()
            ensureActive()
            coroutineScope {
                launch { liveRepository.observeLiveInvitationUpdates(normalizedId) }
                pollLiveCollaboration(
                    refreshInvitations = { liveRepository.refreshGuestInvitations(normalizedId) },
                    refreshCapabilities = { liveRepository.getLiveCollaborationCapabilities() },
                    refreshRoom = { liveRepository.refreshActiveLiveStream(normalizedId) },
                    refreshBattle = { liveRepository.refreshActiveBattle(normalizedId) },
                    refreshSupporters = { liveRepository.refreshLiveSupporters(normalizedId) },
                    refreshGifts = { liveRepository.refreshLiveGiftEvents(normalizedId) }
                )
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
        publishCamera: Boolean = false,
        connectMediaAfterAccept: Boolean = true
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.respondToGuestInvitation(liveId, invitationId, accept)
                .onSuccess { invitation ->
                    val currentUserId = authService.getCurrentUserId()?.trim().orEmpty()
                    ensureActive()
                    if (connectMediaAfterAccept && accept && invitation.inviteeUserId.equals(currentUserId, ignoreCase = true)) {
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

    fun startCrossLiveBattle(liveId: String, opponentLiveId: String, opponentUserId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.startBattle(liveId, opponentUserId, opponentLiveId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo enviar la invitación de batalla.") }
        }
    }

    fun refreshBattleCandidates(liveId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.refreshBattleCandidates(liveId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudieron cargar otros creadores en LIVE.") }
        }
    }

    fun refreshActiveBattle(liveId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.refreshActiveBattle(liveId)
                .onFailure { error ->
                    android.util.Log.w("LiveBattle", "No se pudo actualizar la batalla", error)
                }
        }
    }

    fun respondToCrossLiveBattle(liveId: String, battleId: String, accepted: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.respondToCrossLiveBattle(liveId, battleId, accepted)
                .onSuccess {
                    if (accepted) {
                        liveRepository.connectLiveKit(
                            liveId = liveId,
                            role = "host",
                            publishAudio = isMicEnabled.value,
                            publishCamera = isVideoEnabled.value && !isPaused.value,
                        ).onFailure { error ->
                            _uiError.value = error.toUserFacingMessage("La batalla inició, pero no se pudo abrir la sala compartida.")
                        }
                    }
                }
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo responder la batalla.") }
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
            hostPreparationJob?.cancel()
            _hostPreparation.value = LiveHostPreparationState.Failed("El identificador del live no es válido.")
            return
        }

        when (val current = _hostPreparation.value) {
            is LiveHostPreparationState.Loading -> if (current.liveId == normalizedId) return
            is LiveHostPreparationState.Ready -> if (current.requirements.liveId == normalizedId) return
            else -> Unit
        }

        hostPreparationJob?.cancel()
        _hostPreparation.value = LiveHostPreparationState.Loading(normalizedId)
        hostPreparationJob = viewModelScope.launch {
            try {
                val stream = withContext(Dispatchers.IO) {
                    liveRepository.getLiveStreamById(normalizedId).getOrThrow()
                }
                // A previous lookup must not overwrite a newer host setup screen.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
                        videoEnabled = liveRepository.initialHostVideoEnabled(liveSourceFromWire(stream.source))
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _hostPreparation.value = LiveHostPreparationState.Failed(
                    error.toUserFacingMessage("No se pudo preparar el live.")
                )
            }
        }
    }

    fun host(liveId: String) {
        startSession(liveId = liveId, role = LiveRole.HOST) { id ->
            liveRepository.hostLive(id)
        }
    }

    fun releaseUnusedSetupPreview() {
        liveRepository.stopSetupCameraPreviewAsync()
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
                val result = conectate.com.data.repository.awaitLiveConnection { start(normalizedId) }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (result.isFailure) {
                    val fallback = if (role == LiveRole.HOST) {
                        "No se pudo iniciar el live."
                    } else {
                        "No se pudo abrir el live."
                    }
                    _uiError.value = result.exceptionOrNull().toUserFacingMessage(fallback)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiError.value = error.toUserFacingMessage("No se pudo conectar al live. Inténtalo de nuevo.")
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

    fun prepareLiveMusic(
        uri: Uri,
        displayName: String,
        startMs: Long = 0L,
        endMs: Long? = null,
    ) {
        viewModelScope.launch {
            liveRepository.prepareLiveMusic(uri, displayName, startMs, endMs)
                .onFailure { _uiError.value = it.message ?: "No se pudo preparar la canción" }
        }
    }

    fun setLiveMusicEnabled(enabled: Boolean) = liveRepository.setLiveMusicEnabled(enabled)

    fun setLiveMusicVolume(volume: Float) = liveRepository.setLiveMusicVolume(volume)

    fun clearLiveMusic() = liveRepository.clearLiveMusic()

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

    fun consumeSetupScreenProjectionIntent(): Intent? =
        liveRepository.consumeSetupScreenProjectionIntent()

    fun startCamera(): Boolean {
        return liveRepository.startCamera()
    }

    fun setCameraFilter(filter: LiveCameraFilter) {
        liveRepository.setCameraFilter(filter)
    }

    fun setCreatorLayoutStyle(style: conectate.com.data.model.LiveCreatorLayoutStyle) {
        liveRepository.setCreatorLayoutStyle(style)
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

    fun requestGuestAccess() = liveRepository.requestGuestAccess()

    fun cancelGuestAccessRequest() = liveRepository.cancelGuestAccessRequest()

    fun respondToGuestRequest(socketId: String, accepted: Boolean) {
        liveRepository.respondToGuestRequest(socketId, accepted)
    }

    fun startGuestBroadcast(videoEnabled: Boolean, micEnabled: Boolean): Boolean =
        liveRepository.startGuestBroadcast(videoEnabled, micEnabled)

    fun setGuestMicEnabled(enabled: Boolean) = liveRepository.setGuestMicEnabled(enabled)

    fun setGuestVideoEnabled(enabled: Boolean): Boolean = liveRepository.setGuestVideoEnabled(enabled)

    fun leaveGuestSeat() = liveRepository.leaveGuestSeat()

    fun refreshGifts() {
        viewModelScope.launch(Dispatchers.IO) {
            walletRepository.fetchAvailableGifts()
            walletRepository.fetchBalance()
        }
    }

    fun startBattle(opponentSocketId: String, durationSeconds: Int) {
        liveRepository.startBattle(opponentSocketId, durationSeconds)
    }

    fun respondToBattle(accepted: Boolean) = liveRepository.respondToBattle(accepted)

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
