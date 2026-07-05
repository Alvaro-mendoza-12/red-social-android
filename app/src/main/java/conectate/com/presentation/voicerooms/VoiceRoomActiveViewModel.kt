package conectate.com.presentation.voicerooms

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.VoiceParticipant
import conectate.com.data.model.VoiceRoom
import conectate.com.data.repository.VoiceRoomRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class VoiceRoomActiveViewModel @Inject constructor(
    private val voiceRoomRepository: VoiceRoomRepository,
    private val authService: AuthService
) : ViewModel() {
    private companion object {
        const val REACTION_VISIBLE_MS = 2_600L
        const val LOCAL_ECHO_WINDOW_MS = 3_000L
    }

    private val _room = MutableStateFlow<VoiceRoom?>(null)
    val room: StateFlow<VoiceRoom?> = _room.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeaker = MutableStateFlow(true)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker.asStateFlow()

    private val _isJoined = MutableStateFlow(false)
    val isJoined: StateFlow<Boolean> = _isJoined.asStateFlow()

    private val _joinError = MutableStateFlow<String?>(null)
    val joinError: StateFlow<String?> = _joinError.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _participants = MutableStateFlow<List<VoiceParticipant>>(emptyList())
    val participants: StateFlow<List<VoiceParticipant>> = _participants.asStateFlow()

    private val _videoMode = MutableStateFlow(VoiceRoomRepository.VideoMode.NONE)
    val videoMode: StateFlow<VoiceRoomRepository.VideoMode> = _videoMode.asStateFlow()
    val videoError: StateFlow<String?> = voiceRoomRepository.videoError

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _peerConnectionStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerConnectionStates: StateFlow<Map<String, String>> = _peerConnectionStates.asStateFlow()

    private val _signalingStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val signalingStates: StateFlow<Map<String, String>> = _signalingStates.asStateFlow()

    private val _debugInfo = MutableStateFlow<String?>(null)
    val debugInfo: StateFlow<String?> = _debugInfo.asStateFlow()

    private val _fullScreenParticipantId = MutableStateFlow<String?>(null)
    val fullScreenParticipantId: StateFlow<String?> = _fullScreenParticipantId.asStateFlow()

    private val _cinemaState = MutableStateFlow<CinemaState?>(null)
    val cinemaState: StateFlow<CinemaState?> = _cinemaState.asStateFlow()

    val cinemaSync = _cinemaState

    private val _isPlayerFullscreen = MutableStateFlow(false)
    val isPlayerFullscreen: StateFlow<Boolean> = _isPlayerFullscreen.asStateFlow()

    fun setPlayerFullscreen(fullscreen: Boolean) {
        _isPlayerFullscreen.value = fullscreen
    }

    private val _unreadChatCount = MutableStateFlow(0)
    val unreadChatCount: StateFlow<Int> = _unreadChatCount.asStateFlow()

    private val _showPasswordPrompt = MutableStateFlow(false)
    val showPasswordPrompt: StateFlow<Boolean> = _showPasswordPrompt.asStateFlow()

    private val _passwordError = MutableStateFlow<String?>(null)
    val passwordError: StateFlow<String?> = _passwordError.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _activeReactions = MutableStateFlow<List<ReactionEvent>>(emptyList())
    val activeReactions: StateFlow<List<ReactionEvent>> = _activeReactions.asStateFlow()
    private val reactionExpiryJobs = mutableMapOf<String, Job>()
    private var videoActionJob: Job? = null

    private val _inviteQuery = MutableStateFlow("")
    val inviteQuery: StateFlow<String> = _inviteQuery.asStateFlow()

    private val _inviteResults = MutableStateFlow<List<UserInvite>>(emptyList())
    val inviteResults: StateFlow<List<UserInvite>> = _inviteResults.asStateFlow()

    private val _isSearchingInvites = MutableStateFlow(false)
    val isSearchingInvites: StateFlow<Boolean> = _isSearchingInvites.asStateFlow()

    private var joinedRoomId: String? = null

    init {
        viewModelScope.launch {
            voiceRoomRepository.currentRoom.collectLatest { room ->
                if (room != null) {
                    _room.value = room
                }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.currentParticipants.collectLatest { participants ->
                _participants.value = participants
                val selfId = _currentUserId.value?.trim()?.lowercase().orEmpty()
                if (selfId.isNotBlank()) {
                    _isMuted.value = participants.firstOrNull {
                        it.id.trim().lowercase() == selfId
                    }?.isMuted ?: _isMuted.value
                }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.currentVideoMode.collectLatest { mode ->
                _videoMode.value = mode
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.remoteVideoTracks.collectLatest { tracks ->
                _remoteVideoTracks.value = tracks
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.localVideoTrack.collectLatest { track ->
                _localVideoTrack.value = track
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.peerConnectionStates.collectLatest { states ->
                _peerConnectionStates.value = states.mapValues { it.value.name }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.signalingStates.collectLatest { states ->
                _signalingStates.value = states.mapValues { it.value.name }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.debugInfo.collectLatest { info ->
                _debugInfo.value = info.entries
                    .take(6)
                    .joinToString("\n") { "${it.key}: ${it.value}" }
                    .ifBlank { null }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.sessionState.collectLatest { session ->
                _isJoined.value = session.isConnected
                _videoMode.value = session.videoMode
                _isMuted.value = session.isMuted
                if (session.connectionState == conectate.com.data.repository.VoiceConnectionState.ERROR) {
                    _joinError.value = session.connectionError ?: "No se pudo conectar con la sala de voz."
                } else if (session.isConnected) {
                    _joinError.value = null
                }
                session.room?.let { _room.value = it }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.cinemaState.collectLatest { state ->
                _cinemaState.value = state?.let {
                    CinemaState(
                        isCinemaMode = it.isCinemaMode,
                        videoUrl = it.videoUrl.orEmpty(),
                        videoTitle = it.videoTitle.orEmpty(),
                        isPlaying = it.isPlaying,
                        currentPosition = it.currentPosition
                    )
                }
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.incomingMessages.collectLatest { message ->
                _messages.value = (_messages.value + message).distinctBy { it.id }
                _unreadChatCount.value += 1
            }
        }
        viewModelScope.launch {
            voiceRoomRepository.incomingReactions.collectLatest { reaction ->
                if (isRecentLocalEcho(reaction)) return@collectLatest
                appendReaction(reaction)
            }
        }
    }

    fun getEglBaseContext(): org.webrtc.EglBase.Context? {
        return voiceRoomRepository.getEglBaseContext()
    }

    fun joinRoom(roomId: String) {
        viewModelScope.launch {
            _joinError.value = null
            val seed = authService.getCurrentUserProfileSeed()
            val userId = voiceRoomRepository.getCurrentUserId()
                ?: seed?.id
                ?: run {
                    _joinError.value = "No se pudo identificar tu sesión. Vuelve a iniciar sesión."
                    return@launch
                }
            _currentUserId.value = userId
            try {
                val roomResult = voiceRoomRepository.getRoomById(roomId)
                val resolvedRoom = roomResult.getOrThrow()
                _room.value = resolvedRoom
                if (joinedRoomId == resolvedRoom.id && _isJoined.value) return@launch
                joinedRoomId = resolvedRoom.id

                val displayName = seed?.displayName
                    ?: seed?.fullName
                    ?: seed?.email?.substringBefore("@")
                    ?: "Usuario"
                voiceRoomRepository.joinRoom(
                    roomId = resolvedRoom.id,
                    userId = userId,
                    displayName = displayName,
                    avatarUrl = seed?.avatarUrl,
                    signalingRoomId = roomId
                )
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomActiveVM", "Error joining room", e)
                _isJoined.value = false
                _joinError.value = e.message ?: "No se pudo entrar a la sala de voz."
            }
        }
    }

    fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        voiceRoomRepository.toggleMute(next)
    }

    fun toggleSpeaker() {
        val next = !_isSpeaker.value
        _isSpeaker.value = next
        voiceRoomRepository.toggleSpeaker(next)
    }

    fun toggleCamera() {
        if (videoActionJob?.isActive == true) return
        videoActionJob = viewModelScope.launch {
            if (!_isJoined.value) {
                _joinError.value = "Espera a que la sala termine de conectar."
                return@launch
            }
            runCatching {
                if (_videoMode.value == VoiceRoomRepository.VideoMode.CAMERA) {
                    voiceRoomRepository.stopVideo()
                } else {
                    voiceRoomRepository.startCameraCapture()
                }
            }.onFailure {
                _joinError.value = "No se pudo cambiar la cámara. Intenta otra vez."
            }
            delay(2_200)
        }
    }

    fun toggleScreenShare(data: Intent) {
        if (videoActionJob?.isActive == true) return
        videoActionJob = viewModelScope.launch {
            if (!_isJoined.value) {
                _joinError.value = "Espera a que la sala termine de conectar."
                return@launch
            }
            runCatching {
                if (_videoMode.value == VoiceRoomRepository.VideoMode.SCREEN) {
                    voiceRoomRepository.stopVideo()
                } else {
                    voiceRoomRepository.startScreenCapture(data)
                }
            }.onFailure {
                _joinError.value = "No se pudo compartir la pantalla. Vuelve a intentarlo."
            }
            delay(2_200)
        }
    }

    fun stopVideo() {
        runCatching { voiceRoomRepository.stopVideo() }
    }

    fun clearVideoError() {
        voiceRoomRepository.clearVideoError()
    }

    fun leaveRoom() {
        viewModelScope.launch {
            voiceRoomRepository.leaveRoom()
            _isJoined.value = false
            joinedRoomId = null
        }
    }

    fun getCurrentUserId(): String = _currentUserId.value ?: ""

    fun toggleFullScreen(participantId: String?) {
        _fullScreenParticipantId.value = participantId
    }

    fun setParticipantVolume(participantId: String, volume: Float) {
        voiceRoomRepository.setParticipantVolume(participantId, volume.toDouble())
    }

    fun selectVideo(url: String, title: String) {
        val state = CinemaState(
            isCinemaMode = true,
            videoUrl = url,
            videoTitle = title,
            isPlaying = true,
            currentPosition = 0L
        )
        _cinemaState.value = state
        _room.value?.id?.let { roomId ->
            voiceRoomRepository.emitCinemaState(
                roomId,
                conectate.com.data.repository.CinemaState(
                    isCinemaMode = true,
                    videoUrl = url,
                    videoTitle = title,
                    isPlaying = true,
                    currentPosition = 0L
                )
            )
        }
    }

    fun resolveUniversalLink(url: String): VoiceRoomRepository.CinemaResolvedItem? {
        return VoiceRoomRepository.CinemaResolvedItem(url, url, null)
    }

    fun sendReaction(emoji: String) {
        val reaction = ReactionEvent(
            id = UUID.randomUUID().toString(),
            userId = getCurrentUserId(),
            emoji = emoji,
            timestamp = System.currentTimeMillis()
        )
        appendReaction(reaction)
        _room.value?.id?.let { roomId ->
            voiceRoomRepository.sendReaction(roomId, emoji, reaction.id, reaction.timestamp)
        }
    }

    private fun appendReaction(reaction: ReactionEvent) {
        val enriched = reaction.copy(id = reaction.id.ifBlank { UUID.randomUUID().toString() })
        if (_activeReactions.value.any { it.id == enriched.id }) return
        _activeReactions.value = (_activeReactions.value + enriched).distinctBy { it.id }.takeLast(12)
        scheduleReactionExpiry(enriched)
    }

    private fun scheduleReactionExpiry(reaction: ReactionEvent) {
        val reactionId = reaction.id.trim()
        if (reactionId.isBlank()) return
        reactionExpiryJobs[reactionId]?.cancel()
        reactionExpiryJobs[reactionId] = viewModelScope.launch {
            delay(REACTION_VISIBLE_MS)
            _activeReactions.value = _activeReactions.value.filterNot { it.id == reactionId }
            reactionExpiryJobs.remove(reactionId)?.cancel()
        }
    }

    private fun isRecentLocalEcho(reaction: ReactionEvent): Boolean {
        val currentUserId = getCurrentUserId().trim().lowercase()
        val reactionUserId = reaction.userId.trim().lowercase()
        if (currentUserId.isBlank() || reactionUserId != currentUserId) return false
        val now = System.currentTimeMillis()
        return _activeReactions.value.any { active ->
            active.userId.trim().lowercase() == currentUserId &&
                active.emoji == reaction.emoji &&
                now - active.timestamp in 0..LOCAL_ECHO_WINDOW_MS
        }
    }

    fun exitCinemaMode() {
        _cinemaState.value = null
        _room.value?.id?.let { roomId ->
            voiceRoomRepository.emitCinemaState(
                roomId,
                conectate.com.data.repository.CinemaState(isCinemaMode = false)
            )
        }
    }

    fun syncCinema(playing: Boolean, position: Long) {
        _cinemaState.value = _cinemaState.value?.copy(isPlaying = playing, currentPosition = position)
        _room.value?.id?.let { roomId -> voiceRoomRepository.emitCinemaSync(roomId, playing, position) }
    }

    fun controlCinema(action: String, position: Long) {
        when (action) {
            "play" -> _cinemaState.value = _cinemaState.value?.copy(isPlaying = true)
            "pause" -> _cinemaState.value = _cinemaState.value?.copy(isPlaying = false)
            "seek" -> _cinemaState.value = _cinemaState.value?.copy(currentPosition = position)
        }
        _room.value?.id?.let { roomId -> voiceRoomRepository.emitCinemaControl(roomId, action, position) }
    }

    fun updateRoom(name: String, isPublic: Boolean, password: String?) {
        viewModelScope.launch {
            _room.value?.id?.let { roomId ->
                voiceRoomRepository.updateRoom(roomId, name, isPublic, password)
            }
            _room.value = _room.value?.copy(name = name, isPublic = isPublic, password = password)
        }
    }

    fun validatePassword(password: String) {
        _showPasswordPrompt.value = false
    }

    fun markChatRead() {
        _unreadChatCount.value = 0
    }

    fun sendMessage(text: String) {
        viewModelScope.launch {
            val msg = ChatMessage(
                id = System.currentTimeMillis().toString(),
                userId = getCurrentUserId(),
                text = text,
                timestamp = System.currentTimeMillis()
            )
            val current = _messages.value.toMutableList()
            current.add(msg)
            _messages.value = current
            _room.value?.id?.let { roomId -> voiceRoomRepository.sendMessage(roomId, text) }
        }
    }

    fun onInviteQueryChanged(query: String) {
        _inviteQuery.value = query
        viewModelScope.launch {
            if (query.isNotBlank()) {
                _isSearchingInvites.value = true
                // Search users
                kotlinx.coroutines.delay(500)
                _inviteResults.value = emptyList()
                _isSearchingInvites.value = false
            } else {
                _inviteResults.value = emptyList()
            }
        }
    }

    fun inviteUser(user: UserInvite) {
        viewModelScope.launch {
            // Send invite via signaling
        }
    }

    override fun onCleared() {
        reactionExpiryJobs.values.forEach { it.cancel() }
        reactionExpiryJobs.clear()
        super.onCleared()
    }
}

data class CinemaState(
    val isCinemaMode: Boolean,
    val videoUrl: String = "",
    val videoTitle: String = "",
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L
)

data class ChatMessage(
    val id: String,
    val userId: String,
    val text: String,
    val timestamp: Long,
    val userName: String = ""
)

data class ReactionEvent(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String,
    val timestamp: Long,
    val userId: String
)

data class UserInvite(
    val id: String,
    val displayName: String,
    val avatarUrl: String? = null
)
