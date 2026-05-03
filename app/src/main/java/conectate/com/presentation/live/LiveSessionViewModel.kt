package conectate.com.presentation.live

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.LiveRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LiveSessionViewModel @Inject constructor(
    private val liveRepository: LiveRepository,
    private val authService: AuthService,
    private val userRepository: UserRepository
) : ViewModel() {

    val activeSession = liveRepository.activeSession
    val comments = liveRepository.comments
    val reactions = liveRepository.reactions
    val viewerCount = liveRepository.viewerCount
    val isMicEnabled = liveRepository.isMicEnabled
    val isMediaReady = liveRepository.isMediaReady
    val isPaused = liveRepository.isPaused
    val isVideoEnabled = liveRepository.isVideoEnabled
    val currentSource = liveRepository.currentSource
    val eglBaseContext = liveRepository.eglBaseContext
    val localVideoTrack = liveRepository.localVideoTrack
    val remoteVideoTracks = liveRepository.remoteVideoTracks

    private val _isFollowingHost = MutableStateFlow<Boolean?>(null)
    val isFollowingHost: StateFlow<Boolean?> = _isFollowingHost.asStateFlow()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    fun getCurrentUserId(): String? = authService.getCurrentUserId()

    fun host(liveId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = liveRepository.hostLive(liveId)
            if (result.isFailure) {
                _uiError.value = result.exceptionOrNull()?.message ?: "No se pudo iniciar el live."
            }
        }
    }

    fun joinAsViewer(liveId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = liveRepository.joinLiveAsViewer(liveId)
            if (result.isFailure) {
                _uiError.value = result.exceptionOrNull()?.message ?: "No se pudo abrir el live."
            }
        }
    }

    fun endLive() {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.endLive()
        }
    }

    fun toggleMic(enabled: Boolean) {
        liveRepository.toggleMic(enabled)
    }

    fun pauseLive() {
        liveRepository.pauseLive()
    }

    fun resumeLive(screenIntent: Intent? = null): Boolean {
        return screenIntent?.let { liveRepository.resumeLive(it) } ?: false
    }

    fun applyHostConfiguration(
        source: conectate.com.data.model.LiveSource,
        micEnabled: Boolean,
        videoEnabled: Boolean,
        projectionIntent: Intent? = null
    ): Boolean {
        return projectionIntent?.let {
            liveRepository.updateHostConfiguration(
                source = source,
                micEnabled = micEnabled,
                videoEnabled = videoEnabled,
                projectionIntent = it
            )
        } ?: false
    }

    fun canReuseScreenShareIntent(): Boolean = liveRepository.canReuseScreenShareIntent()

    fun startCamera(): Boolean {
        return liveRepository.startCamera()
    }

    fun startScreenShare(intent: Intent): Boolean {
        return liveRepository.startScreenShare(intent)
    }

    fun stopVideo() {
        liveRepository.stopVideo()
    }

    fun leaveLive() {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.endLive()
        }
    }

    fun sendComment(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            liveRepository.sendComment(text)
        }
    }

    fun sendReaction(emoji: String = "❤️") {
        liveRepository.sendReaction(emoji)
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
