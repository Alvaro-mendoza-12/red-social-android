package conectate.com.presentation.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.LiveStream
import conectate.com.data.repository.LiveRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import conectate.com.data.model.LiveSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val liveRepository: LiveRepository,
    private val authService: AuthService
) : ViewModel() {

    val activeStreams = liveRepository.activeStreams
    val liveReplays = liveRepository.liveReplays
    val myLiveHistory = liveRepository.myLiveHistory
    val replayUploadProgress = liveRepository.replayUploadProgress
    val activeSession = liveRepository.activeSession
    val comments = liveRepository.comments
    val reactions = liveRepository.reactions
    val reactionCount = liveRepository.reactionCount
    val viewerCount = liveRepository.viewerCount
    val isMicEnabled = liveRepository.isMicEnabled
    val isVideoEnabled = liveRepository.isVideoEnabled
    val currentSource = liveRepository.currentSource
    val quality = liveRepository.quality
    val localVideoTrack = liveRepository.localVideoTrack
    val remoteVideoTracks = liveRepository.remoteVideoTracks
    val eglBaseContext = liveRepository.eglBaseContext

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _isLoadingStreams = MutableStateFlow(false)
    val isLoadingStreams = _isLoadingStreams.asStateFlow()
    private val _streamsLoadError = MutableStateFlow<String?>(null)
    val streamsLoadError = _streamsLoadError.asStateFlow()

    fun refreshStreams() {
        if (_isLoadingStreams.value) return
        viewModelScope.launch {
            _isLoadingStreams.value = true
            _streamsLoadError.value = null
            try {
                val result = liveRepository.refreshActiveStreams()
                _streamsLoadError.value = result.exceptionOrNull()?.let {
                    "No se pudo comprobar quién está en vivo. Revisa tu conexión e inténtalo de nuevo."
                }
            } finally {
                _isLoadingStreams.value = false
            }
            runCatching { liveRepository.refreshLiveReplays() }
        }
    }

    fun publishReplay(liveId: String, uri: android.net.Uri) {
        viewModelScope.launch {
            liveRepository.publishLiveReplay(liveId, uri)
                .onFailure { _error.value = it.message ?: "No se pudo publicar la repetición." }
        }
    }

    fun startLive(title: String, source: LiveSource) {
        viewModelScope.launch {
            val result = liveRepository.createLiveStream(title, source)
            result.onSuccess { stream ->
                hostLive(stream.id)
            }.onFailure { e ->
                _error.value = e.message
            }
        }
    }

    fun hostLive(streamId: String) {
        viewModelScope.launch {
            val result = liveRepository.hostLive(streamId)
            result.onFailure { e ->
                _error.value = e.message
            }
        }
    }

    fun joinLive(streamId: String) {
        viewModelScope.launch {
            val result = liveRepository.joinLiveAsViewer(streamId)
            result.onFailure { e ->
                _error.value = e.message
            }
        }
    }

    fun sendComment(content: String) {
        viewModelScope.launch {
            liveRepository.sendComment(content)
        }
    }

    fun sendReaction() {
        liveRepository.sendReaction()
    }

    fun toggleMic(enabled: Boolean) {
        liveRepository.toggleMic(enabled)
    }

    fun endLive() {
        viewModelScope.launch {
            liveRepository.endLive()
        }
    }

    fun clearError() {
        _error.value = null
    }
}
