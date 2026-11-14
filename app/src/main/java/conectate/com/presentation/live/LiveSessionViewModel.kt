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
import conectate.com.core.concurrency.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
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
    private val walletRepository: WalletRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // Created before any launch/dispatcher hop; retained callbacks keep this exact receipt.
    private val screenEntry = liveRepository.createScreenEntry()
    private fun isScreenOpen(): Boolean = !screenEntry.closed && screenEntry.account.isCurrent()
    private val screenAccess = combine(screenEntry.changes, authService.accountVisitChanges, liveRepository.activeSession) { _, _, _ ->
        isScreenOpen() to liveRepository.ownsScreenEntry(screenEntry)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, isScreenOpen() to false)

    private fun <T> screenState(source: StateFlow<T>, empty: T, requiresMedia: Boolean = true): StateFlow<T> {
        fun snapshot(): T {
            var current = empty
            if (requiresMedia) {
                liveRepository.commitScreenEntry(screenEntry) { current = source.value }
            } else {
                authService.commitForAccountLease(screenEntry.account) {
                    if (!screenEntry.closed) current = source.value
                }
            }
            return current
        }
        val projected = combine(source, screenAccess) { _, _ ->
            snapshot()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, empty)
        return object : StateFlow<T> by projected {
            override val value: T get() = snapshot()
            override val replayCache: List<T> get() = listOf(value)

            @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
            override suspend fun collect(collector: FlowCollector<T>): Nothing {
                var emitted = false
                var previous = empty
                return projected.collect(object : FlowCollector<T> {
                    override suspend fun emit(value: T) {
                        // The shared cache can predate an account/route invalidation. Read
                        // under the receipt's commit at delivery, including its first replay.
                        val current = snapshot()
                        if (!emitted || previous != current) {
                            emitted = true
                            previous = current
                            collector.emit(current)
                        }
                    }
                })
            }
        }
    }

    private fun screenAction(action: () -> Unit): Boolean = liveRepository.commitScreenEntry(screenEntry, action)
    private fun <T> screenValue(empty: T, action: () -> T): T {
        var result = empty
        screenAction { result = action() }
        return result
    }

    val activeSession = screenState(liveRepository.activeSession, null)
    val isFrontCamera = screenState(liveRepository.isFrontCamera, true)
    val comments = screenState(liveRepository.comments, emptyList())
    val reactions = screenState(liveRepository.reactions, emptyList())
    val reactionCount = screenState(liveRepository.reactionCount, 0L)
    val giftEvents = screenState(liveRepository.giftEvents, emptyList())
    val viewerCount = screenState(liveRepository.viewerCount, 0)
    val audienceStats = screenState(liveRepository.audienceStats, conectate.com.data.model.LiveAudienceStats())
    val liveViewers = screenState(liveRepository.liveViewers, emptyList())
    val guestRequests = screenState(liveRepository.guestRequests, emptyList())
    val activeGuests = screenState(liveRepository.activeGuests, emptyList())
    val guestAccessState = screenState(liveRepository.guestAccessState, conectate.com.data.model.LiveGuestAccessState())
    val isGuestBroadcasting = screenState(liveRepository.isGuestBroadcasting, false)
    val isGuestMicEnabled = screenState(liveRepository.isGuestMicEnabled, false)
    val isGuestVideoEnabled = screenState(liveRepository.isGuestVideoEnabled, false)
    val battleState = screenState(liveRepository.battleState, conectate.com.data.model.LiveBattleState())
    val availableGifts = screenState(walletRepository.availableGifts, emptyList(), requiresMedia = false)
    val coinBalance = screenState(walletRepository.coinBalance, 0, requiresMedia = false)
    val collaborationCapabilities = screenState(liveRepository.collaborationCapabilities, conectate.com.data.model.LiveCollaborationCapabilities())
    val guestInvitations = screenState(liveRepository.guestInvitations, emptyList())
    val activeBattle = screenState(liveRepository.activeBattle, null)
    val battleCandidates = screenState(liveRepository.battleCandidates, emptyList())
    val liveSupporters = screenState(liveRepository.liveSupporters, emptyList())
    val moderationState = screenState(liveRepository.moderationState, conectate.com.data.model.LiveModerationState())
    val isMicEnabled = screenState(liveRepository.isMicEnabled, false)
    val isMediaReady = screenState(liveRepository.isMediaReady, false)
    val isPaused = screenState(liveRepository.isPaused, false)
    val isVideoEnabled = screenState(liveRepository.isVideoEnabled, false)
    val isMediaTransitioning = screenState(liveRepository.isMediaTransitioning, false)
    val currentSource = screenState(liveRepository.currentSource, conectate.com.data.model.LiveSource.CAMERA)
    val cameraFilter = screenState(liveRepository.cameraFilter, LiveCameraFilter.NONE)
    val creatorLayoutStyle = screenState(liveRepository.creatorLayoutStyle, conectate.com.data.model.LiveCreatorLayoutStyle.FUSION)
    val cameraFilterPerformance = screenState(liveRepository.cameraFilterPerformance, conectate.com.data.webrtc.LiveFilterPerformanceState.NATURAL)
    val eglBaseContext = screenState(liveRepository.eglBaseContext, null)
    val localVideoTrack = screenState(liveRepository.localVideoTrack, null)
    val remoteVideoTracks = screenState(liveRepository.remoteVideoTracks, emptyMap())
    val liveKitState: StateFlow<LiveKitRoomBridge.State> = screenState(liveRepository.liveKitState, LiveKitRoomBridge.State.DISCONNECTED)
    val liveKitRemoteVideos = screenState(liveRepository.liveKitRemoteVideos, emptyList())
    val liveKitLocalPreview = screenState(liveRepository.liveKitLocalPreview, null)
    val liveKitRemoteParticipants = screenState(liveRepository.liveKitRemoteParticipants, emptyList())
    val liveMusic = screenState(liveRepository.liveMusic, conectate.com.data.model.LiveMusicPlaybackState())

    private val pointsController = LiveChannelPointsController(
        liveChannelPointsRepository, viewModelScope, authService::captureAccountLease, authService::commitForAccountLease,
        accountVisitChanges = authService.accountVisitChanges, ioDispatcher = dispatchers.io,
    )
    val channelPoints = screenState(pointsController.state, LiveChannelPointsUiState(), requiresMedia = false)

    private val _isFollowingHost = MutableStateFlow<Boolean?>(null)
    val isFollowingHost: StateFlow<Boolean?> = screenState(_isFollowingHost, null, requiresMedia = false)

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = screenState(_uiError, null, requiresMedia = false)

    private val _hostPreparation = MutableStateFlow<LiveHostPreparationState>(LiveHostPreparationState.Idle)
    val hostPreparation: StateFlow<LiveHostPreparationState> = screenState(_hostPreparation, LiveHostPreparationState.Idle, requiresMedia = false)

    private val _connectionRequest = MutableStateFlow<LiveConnectionRequest?>(null)
    private var hostPreparationJob: Job? = null
    private var collaborationPollingJob: Job? = null
    private var collaborationPollingLiveId: String? = null

    init {
        viewModelScope.launch {
            liveRepository.runtimeErrors.collect { message ->
                if (liveRepository.ownsScreenEntry(screenEntry)) _uiError.value = message
            }
        }
        viewModelScope.launch(dispatchers.io) {
            if (!isScreenOpen()) return@launch
            walletRepository.fetchAvailableGifts()
            if (!isScreenOpen()) return@launch
            walletRepository.fetchBalance()
        }
    }

    fun getCurrentUserId(): String? = screenEntry.account.accountId.takeIf { isScreenOpen() }

    internal val accountVisitGeneration: StateFlow<Long> get() = authService.accountVisitChanges

    internal fun captureToolVisit(liveId: String): LiveToolAccountVisit = captureLiveToolAccountVisit(
        liveId, { screenEntry.account }, { accountVisitGeneration.value }, authService::commitForAccountLease,
        entryCurrent = ::isScreenOpen,
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
        collaborationPollingJob = viewModelScope.launch(dispatchers.io) {
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
        viewModelScope.launch(dispatchers.io) {
            liveRepository.inviteLiveGuest(liveId, userId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo enviar la invitación.") }
        }
    }

    fun requestToJoin(liveId: String) {
        viewModelScope.launch(dispatchers.io) {
            liveRepository.requestToJoinLive(liveId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo enviar la solicitud para unirte.") }
        }
    }

    fun setGuestRequestsAllowed(liveId: String, allowed: Boolean) {
        viewModelScope.launch(dispatchers.io) {
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
        viewModelScope.launch(dispatchers.io) {
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
        viewModelScope.launch(dispatchers.io) {
            liveRepository.startBattle(liveId, opponentUserId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo iniciar la batalla.") }
        }
    }

    fun startCrossLiveBattle(liveId: String, opponentLiveId: String, opponentUserId: String) {
        viewModelScope.launch(dispatchers.io) {
            liveRepository.startBattle(liveId, opponentUserId, opponentLiveId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo enviar la invitación de batalla.") }
        }
    }

    fun refreshBattleCandidates(liveId: String) {
        viewModelScope.launch(dispatchers.io) {
            liveRepository.refreshBattleCandidates(liveId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudieron cargar otros creadores en LIVE.") }
        }
    }

    fun refreshActiveBattle(liveId: String) {
        viewModelScope.launch(dispatchers.io) {
            liveRepository.refreshActiveBattle(liveId)
                .onFailure { error ->
                    android.util.Log.w("LiveBattle", "No se pudo actualizar la batalla", error)
                }
        }
    }

    fun respondToCrossLiveBattle(liveId: String, battleId: String, accepted: Boolean) {
        viewModelScope.launch(dispatchers.io) {
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
        viewModelScope.launch(dispatchers.io) {
            liveRepository.endBattle(liveId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo finalizar la batalla.") }
        }
    }

    fun removeGuest(liveId: String, guestUserId: String) {
        viewModelScope.launch(dispatchers.io) {
            liveRepository.removeLiveGuest(liveId, guestUserId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo retirar al invitado.") }
        }
    }

    fun muteGuest(liveId: String, guestUserId: String) {
        viewModelScope.launch(dispatchers.io) {
            liveRepository.muteLiveGuest(liveId, guestUserId)
                .onFailure { _uiError.value = it.toUserFacingMessage("No se pudo silenciar al invitado.") }
        }
    }

    fun prepareHost(liveId: String) {
        if (!isScreenOpen()) return
        val normalizedId = normalizeLiveSessionId(liveId)
        if (normalizedId == null) {
            hostPreparationJob?.cancel()
            _hostPreparation.value = LiveHostPreparationState.Failed("El identificador del live no es válido.")
            return
        }
        if (!screenEntry.accepts(normalizedId, LiveRole.HOST)) return
        liveRepository.adoptScreenSetupPreview(screenEntry)

        when (val current = _hostPreparation.value) {
            is LiveHostPreparationState.Loading -> if (current.liveId == normalizedId) return
            is LiveHostPreparationState.Ready -> if (current.requirements.liveId == normalizedId) return
            else -> Unit
        }

        hostPreparationJob?.cancel()
        _hostPreparation.value = LiveHostPreparationState.Loading(normalizedId)
        hostPreparationJob = viewModelScope.launch {
            try {
                val stream = withContext(dispatchers.io) {
                    screenEntry.account.requireCurrent()
                    liveRepository.getLiveStreamById(normalizedId).getOrThrow()
                }
                // A previous lookup must not overwrite a newer host setup screen.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!isScreenOpen()) return@launch
                liveRepository.adoptScreenSetupPreview(screenEntry)
                val currentUserId = screenEntry.account.accountId.orEmpty()
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
                if (isScreenOpen()) _hostPreparation.value = LiveHostPreparationState.Failed(
                    error.toUserFacingMessage("No se pudo preparar el live.")
                )
            }
        }
    }

    fun host(liveId: String) {
        startSession(liveId = liveId, role = LiveRole.HOST) { id ->
            liveRepository.hostLive(id, screenEntry)
        }
    }

    fun releaseUnusedSetupPreview() {
        liveRepository.stopScreenSetupPreview(screenEntry)
    }

    fun joinAsViewer(liveId: String) {
        startSession(liveId = liveId, role = LiveRole.VIEWER) { id ->
            liveRepository.joinLiveAsViewer(id, screenEntry)
        }
    }

    private fun startSession(
        liveId: String,
        role: LiveRole,
        start: suspend (String) -> Result<Unit>
    ) {
        if (!isScreenOpen()) return
        val normalizedId = normalizeLiveSessionId(liveId)
        if (normalizedId == null) {
            _uiError.value = "El identificador del live no es válido."
            return
        }

        if (!screenEntry.accepts(normalizedId, role)) {
            _uiError.value = "Esta pantalla pertenece a otro LIVE. Vuelve a abrirlo."
            return
        }

        val request = LiveConnectionRequest(liveId = normalizedId, role = role)
        if (_connectionRequest.value == request) return
        if (_connectionRequest.value != null) {
            _uiError.value = "Ya se está preparando otro live."
            return
        }

        _connectionRequest.value = request
        val startup = viewModelScope.launch(dispatchers.io, start = CoroutineStart.LAZY) {
            try {
                val result = conectate.com.data.repository.awaitLiveConnection { start(normalizedId) }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (result.isFailure && isScreenOpen()) {
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
                if (isScreenOpen()) _uiError.value = error.toUserFacingMessage("No se pudo conectar al live. Inténtalo de nuevo.")
            } finally {
                screenEntry.clearStartup(currentCoroutineContext()[Job])
                if (_connectionRequest.value == request) {
                    _connectionRequest.value = null
                }
            }
        }
        if (screenEntry.registerStartup(startup) && isScreenOpen()) startup.start()
        else { startup.cancel(); _connectionRequest.value = null }
    }

    fun endLive() {
        liveRepository.closeScreenEntry(screenEntry)
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

    suspend fun endLiveAndAwait(): Result<Unit> = liveRepository.closeScreenEntry(screenEntry).await()

    fun toggleMic(enabled: Boolean) {
        screenAction { liveRepository.toggleMic(enabled) }
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

    fun setLiveMusicEnabled(enabled: Boolean) { screenAction { liveRepository.setLiveMusicEnabled(enabled) } }

    fun setLiveMusicVolume(volume: Float) { screenAction { liveRepository.setLiveMusicVolume(volume) } }

    fun clearLiveMusic() { screenAction { liveRepository.clearLiveMusic() } }

    fun pauseLive() {
        screenAction { liveRepository.pauseLive() }
    }

    fun resumeLive(screenIntent: Intent? = null): Boolean {
        return screenValue(false) { liveRepository.resumeLive(screenIntent) }
    }

    fun applyHostConfiguration(
        source: conectate.com.data.model.LiveSource,
        micEnabled: Boolean,
        videoEnabled: Boolean,
        projectionIntent: Intent? = null
    ): Boolean {
        return screenValue(false) { liveRepository.updateHostConfiguration(
            source = source,
            micEnabled = micEnabled,
            videoEnabled = videoEnabled,
            projectionIntent = projectionIntent
        ) }
    }

    fun canReuseScreenShareIntent(): Boolean = screenValue(false) { liveRepository.canReuseScreenShareIntent() }

    fun consumeSetupScreenProjectionIntent(): Intent? =
        screenValue<Intent?>(null) { liveRepository.consumeSetupScreenProjectionIntent() }

    fun startCamera(): Boolean {
        return screenValue(false) { liveRepository.startCamera() }
    }

    fun setCameraFilter(filter: LiveCameraFilter) {
        screenAction { liveRepository.setCameraFilter(filter) }
    }

    fun setCreatorLayoutStyle(style: conectate.com.data.model.LiveCreatorLayoutStyle) {
        screenAction { liveRepository.setCreatorLayoutStyle(style) }
    }

    fun switchCamera(): Boolean = screenValue(false) { liveRepository.switchCamera() }

    fun startScreenShare(intent: Intent): Boolean {
        return screenValue(false) { liveRepository.startScreenShare(intent) }
    }

    fun stopVideo() {
        screenAction { liveRepository.stopVideo() }
    }

    fun leaveLive() {
        stopCollaborationPolling()
        liveRepository.closeScreenEntry(screenEntry)
    }

    suspend fun leaveLiveAndAwait(): Result<Unit> {
        stopCollaborationPolling()
        return liveRepository.closeScreenEntry(screenEntry).await()
    }

    private fun stopCollaborationPolling() {
        collaborationPollingJob?.cancel()
        collaborationPollingJob = null
        collaborationPollingLiveId = null
    }

    fun sendComment(text: String) {
        viewModelScope.launch(dispatchers.io) {
            val result = liveRepository.sendComment(text)
            if (result.isFailure) {
                _uiError.value = result.exceptionOrNull().toUserFacingMessage("No se pudo enviar el comentario.")
            }
        }
    }

    fun sendReaction() {
        screenAction { liveRepository.sendReaction() }
    }

    fun announceCommittedGift(receipt: GiftReceipt) {
        screenAction { liveRepository.announceCommittedGift(receipt) }
    }

    fun requestGuestAccess() { screenAction { liveRepository.requestGuestAccess() } }

    fun cancelGuestAccessRequest() { screenAction { liveRepository.cancelGuestAccessRequest() } }

    fun respondToGuestRequest(socketId: String, accepted: Boolean) {
        screenAction { liveRepository.respondToGuestRequest(socketId, accepted) }
    }

    fun startGuestBroadcast(videoEnabled: Boolean, micEnabled: Boolean): Boolean =
        screenValue(false) { liveRepository.startGuestBroadcast(videoEnabled, micEnabled) }

    fun setGuestMicEnabled(enabled: Boolean) { screenAction { liveRepository.setGuestMicEnabled(enabled) } }

    fun setGuestVideoEnabled(enabled: Boolean): Boolean = screenValue(false) { liveRepository.setGuestVideoEnabled(enabled) }

    fun leaveGuestSeat() { screenAction { liveRepository.leaveGuestSeat() } }

    fun refreshGifts() {
        viewModelScope.launch(dispatchers.io) {
            walletRepository.fetchAvailableGifts()
            walletRepository.fetchBalance()
        }
    }

    fun startBattle(opponentSocketId: String, durationSeconds: Int) {
        screenAction { liveRepository.startBattle(opponentSocketId, durationSeconds) }
    }

    fun respondToBattle(accepted: Boolean) { screenAction { liveRepository.respondToBattle(accepted) } }

    fun updateModerationSettings(chatPaused: Boolean, blockedWords: List<String>) {
        screenAction { liveRepository.updateLiveModerationSettings(
            chatPaused = chatPaused,
            blockedWords = blockedWords
        ) }
    }

    fun updateModerationPause(chatPaused: Boolean) {
        screenAction { liveRepository.updateLiveModerationSettings(chatPaused = chatPaused) }
    }

    fun setModerator(userId: String, enabled: Boolean) {
        screenAction { liveRepository.setLiveModerator(userId, enabled) }
    }

    fun moderateViewer(userId: String, ban: Boolean) {
        screenAction { liveRepository.moderateLiveViewer(userId, ban) }
    }

    fun markReplayMoment(title: String, onSaved: () -> Unit = {}) {
        viewModelScope.launch(dispatchers.io) {
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
        viewModelScope.launch(dispatchers.io) {
            val isFollowing = userRepository.checkIsFollowing(id).getOrNull() ?: false
            _isFollowingHost.value = isFollowing
        }
    }

    fun toggleFollow(hostUserId: String) {
        val id = hostUserId.trim()
        if (id.isBlank()) return
        viewModelScope.launch(dispatchers.io) {
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

    override fun onCleared() {
        liveRepository.abandonScreenEntry(screenEntry)
        super.onCleared()
    }
}
