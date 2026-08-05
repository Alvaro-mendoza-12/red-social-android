package conectate.com.presentation.live

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.LiveRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.normalizeLiveSessionId
import conectate.com.data.supabase.AuthService
import conectate.com.data.model.LiveRole
import conectate.com.data.livekit.LiveKitRoomBridge
import conectate.com.data.model.GiftReceipt
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

@HiltViewModel
class LiveSessionViewModel @Inject constructor(
    private val liveRepository: LiveRepository,
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

    private val _isFollowingHost = MutableStateFlow<Boolean?>(null)
    val isFollowingHost: StateFlow<Boolean?> = _isFollowingHost.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    private val _hostPreparation = MutableStateFlow<LiveHostPreparationState>(LiveHostPreparationState.Idle)
    val hostPreparation: StateFlow<LiveHostPreparationState> = _hostPreparation.asStateFlow()

    private val _connectionRequest = MutableStateFlow<LiveConnectionRequest?>(null)
    private var collaborationPollingJob: Job? = null
    private var collaborationPollingLiveId: String? = null

    init {
        viewModelScope.launch {
            liveRepository.runtimeErrors.collect { message ->
                _uiError.value = message
            }
        }
    }

    fun getCurrentUserId(): String? = authService.getCurrentUserId()

    fun startCollaborationPolling(liveId: String) {
        val normalizedId = normalizeLiveSessionId(liveId) ?: return
        if (collaborationPollingJob?.isActive == true && collaborationPollingLiveId == normalizedId) return
        collaborationPollingJob?.cancel()
        collaborationPollingLiveId = normalizedId
        collaborationPollingJob = viewModelScope.launch(Dispatchers.IO) {
            liveRepository.getLiveCollaborationCapabilities()
            while (true) {
                liveRepository.refreshGuestInvitations(normalizedId)
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

    fun respondToGuestInvitation(liveId: String, invitationId: String, accept: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.respondToGuestInvitation(liveId, invitationId, accept)
                .onSuccess {
                    if (accept) {
                        liveRepository.connectLiveKit(
                            liveId = liveId,
                            role = "guest",
                            publishAudio = true,
                            publishCamera = false
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
