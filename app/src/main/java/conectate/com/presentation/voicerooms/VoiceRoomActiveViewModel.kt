package conectate.com.presentation.voicerooms

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.VoiceParticipant
import conectate.com.data.model.VoiceRoom
import conectate.com.data.repository.VoiceRoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import javax.inject.Inject

@HiltViewModel
class VoiceRoomActiveViewModel @Inject constructor(
    private val voiceRoomRepository: VoiceRoomRepository
) : ViewModel() {

    private val _room = MutableStateFlow<VoiceRoom?>(null)
    val room: StateFlow<VoiceRoom?> = _room.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeaker = MutableStateFlow(true)
    val isSpeaker: StateFlow<Boolean> = _isSpeaker.asStateFlow()

    private val _isJoined = MutableStateFlow(false)
    val isJoined: StateFlow<Boolean> = _isJoined.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _participants = MutableStateFlow<List<VoiceParticipant>>(emptyList())
    val participants: StateFlow<List<VoiceParticipant>> = _participants.asStateFlow()

    private val _videoMode = MutableStateFlow(VoiceRoomRepository.VideoMode.NONE)
    val videoMode: StateFlow<VoiceRoomRepository.VideoMode> = _videoMode.asStateFlow()

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _peerConnectionStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerConnectionStates: StateFlow<Map<String, String>> = _peerConnectionStates.asStateFlow()

    private val _signalingStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val signalingStates: StateFlow<Map<String, String>> = _signalingStates.asStateFlow()

    private val _sharingStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val sharingStates: StateFlow<Map<String, Boolean>> = _sharingStates.asStateFlow()

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

    private val _inviteQuery = MutableStateFlow("")
    val inviteQuery: StateFlow<String> = _inviteQuery.asStateFlow()

    private val _inviteResults = MutableStateFlow<List<UserInvite>>(emptyList())
    val inviteResults: StateFlow<List<UserInvite>> = _inviteResults.asStateFlow()

    private val _isSearchingInvites = MutableStateFlow(false)
    val isSearchingInvites: StateFlow<Boolean> = _isSearchingInvites.asStateFlow()

    private var eglBase: EglBase? = null

    fun getEglBaseContext(): org.webrtc.EglBase.Context {
        val activeEglBase = eglBase ?: EglBase.create().also { eglBase = it }
        return activeEglBase.eglBaseContext
    }

    fun joinRoom(roomId: String) {
        viewModelScope.launch {
            _currentUserId.value = voiceRoomRepository.getCurrentUserId()
            try {
                val roomResult = voiceRoomRepository.getRoomById(roomId)
                _room.value = roomResult.getOrNull()
                _isJoined.value = true
            } catch (e: Exception) {
                android.util.Log.e("VoiceRoomActiveVM", "Error joining room", e)
            }
        }
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
    }

    fun toggleSpeaker() {
        _isSpeaker.value = !_isSpeaker.value
    }

    fun toggleCamera() {
        viewModelScope.launch {
            if (_videoMode.value == VoiceRoomRepository.VideoMode.NONE) {
                _videoMode.value = VoiceRoomRepository.VideoMode.CAMERA
            } else {
                _videoMode.value = VoiceRoomRepository.VideoMode.NONE
            }
        }
    }

    fun toggleScreenShare(data: Intent) {
        viewModelScope.launch {
            _videoMode.value = VoiceRoomRepository.VideoMode.SCREEN
        }
    }

    fun stopVideo() {
        _videoMode.value = VoiceRoomRepository.VideoMode.NONE
    }

    fun leaveRoom() {
        viewModelScope.launch {
            voiceRoomRepository.leaveRoom()
            _isJoined.value = false
            eglBase?.release()
            eglBase = null
        }
    }

    fun getCurrentUserId(): String = _currentUserId.value ?: ""

    fun toggleFullScreen(participantId: String?) {
        _fullScreenParticipantId.value = participantId
    }

    fun setParticipantVolume(participantId: String, volume: Float) {
        // Volume control via WebRTC
    }

    fun selectVideo(url: String, title: String) {
        _cinemaState.value = CinemaState(
            isCinemaMode = true,
            videoUrl = url,
            videoTitle = title,
            isPlaying = true,
            currentPosition = 0L
        )
    }

    fun resolveUniversalLink(url: String): VoiceRoomRepository.CinemaResolvedItem? {
        return VoiceRoomRepository.CinemaResolvedItem(url, url, null)
    }

    fun sendReaction(emoji: String) {
        val reaction = ReactionEvent(
            emoji = emoji,
            timestamp = System.currentTimeMillis(),
            userId = getCurrentUserId()
        )
        val current = _activeReactions.value.toMutableList()
        current.add(reaction)
        _activeReactions.value = current
        // Remove after animation
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            val updated = _activeReactions.value.filter { it.timestamp > System.currentTimeMillis() - 4000 }
            _activeReactions.value = updated
        }
    }

    fun exitCinemaMode() {
        _cinemaState.value = null
    }

    fun syncCinema(playing: Boolean, position: Long) {
        _cinemaState.value = _cinemaState.value?.copy(isPlaying = playing, currentPosition = position)
    }

    fun controlCinema(action: String, position: Long) {
        when (action) {
            "play" -> _cinemaState.value = _cinemaState.value?.copy(isPlaying = true)
            "pause" -> _cinemaState.value = _cinemaState.value?.copy(isPlaying = false)
            "seek" -> _cinemaState.value = _cinemaState.value?.copy(currentPosition = position)
        }
    }

    fun updateRoom(name: String, isPublic: Boolean, password: String?) {
        viewModelScope.launch {
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
        super.onCleared()
        eglBase?.release()
        eglBase = null
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
    val emoji: String,
    val timestamp: Long,
    val userId: String
)

data class UserInvite(
    val id: String,
    val displayName: String,
    val avatarUrl: String? = null
)
