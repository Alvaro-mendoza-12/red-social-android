package conectate.com.presentation.videos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import conectate.com.data.model.Video
import conectate.com.data.repository.VideoRepository
import conectate.com.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.media.MediaPlayer
import conectate.com.util.AudioRecorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject

sealed class VideosUiState {
    object Loading : VideosUiState()
    data class Success(val videos: List<Video>) : VideosUiState()
    data class Error(val message: String) : VideosUiState()
}

@HiltViewModel
class VideosViewModel @Inject constructor(
    val videoRepository: VideoRepository,
    val userRepository: UserRepository,
    private val voiceMessageManager: conectate.com.presentation.messages.VoiceMessageManager,
    private val authService: conectate.com.data.supabase.AuthService,
    private val shareRepository: conectate.com.data.repository.ShareRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val likeRequestsInFlight = mutableSetOf<String>()
    private val shareRequestsInFlight = mutableSetOf<String>()
    private val reportRequestsInFlight = mutableSetOf<String>()

    private val _videosState = MutableStateFlow<VideosUiState>(VideosUiState.Loading)
    val videosState: StateFlow<VideosUiState> = _videosState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    private var videosJob: Job? = null
    private val cachePrefsName = "videos_feed_cache"
    private val cacheKey = "latest_videos_json"
    private val json = Json { ignoreUnknownKeys = true }

    init {
        voiceMessageManager.setScope(viewModelScope)
        loadVideos()
    }

    private fun loadVideos() {
        videosJob?.cancel()
        videosJob = viewModelScope.launch {
            try {
                videoRepository.getVideos().collect { videos ->
                    if (videos.isNotEmpty()) {
                        saveVideosCache(videos)
                        _videosState.value = VideosUiState.Success(videos)
                    } else {
                        val cached = readVideosCache()
                        _videosState.value = VideosUiState.Success(cached)
                    }
                }
            } catch (e: Exception) {
                val cached = readVideosCache()
                if (cached.isNotEmpty()) {
                    _videosState.value = VideosUiState.Success(cached)
                } else {
                    _videosState.value = VideosUiState.Error(
                        e.message ?: "Error al cargar videos"
                    )
                }
            }
        }
    }

    fun refreshVideos() {
        if (_searchQuery.value.isBlank()) {
            loadVideos()
        } else {
            searchVideos(_searchQuery.value)
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isEmpty()) {
            loadVideos()
        } else {
            searchVideos(query)
        }
    }

    private fun searchVideos(query: String) {
        viewModelScope.launch {
            try {
                val result = videoRepository.searchVideos(query)
                result.fold(
                    onSuccess = { videos ->
                        _videosState.value = VideosUiState.Success(videos)
                    },
                    onFailure = { error ->
                        _videosState.value = VideosUiState.Error(
                            error.message ?: "Error en la búsqueda"
                        )
                    }
                )
            } catch (e: Exception) {
                _videosState.value = VideosUiState.Error(e.message ?: "Error")
            }
        }
    }

    fun likeVideo(videoId: String, isLiked: Boolean) {
        if (!likeRequestsInFlight.add(videoId)) return
        // Optimistic Update
        val currentState = _videosState.value
        if (currentState is VideosUiState.Success) {
            val updatedVideos = currentState.videos.map { video ->
                if (video.id == videoId) {
                    val newLikeCount = maxOf(0, if (isLiked) video.likeCount - 1 else video.likeCount + 1)
                    video.copy(likedByMe = !isLiked, likeCount = newLikeCount)
                } else video
            }
            _videosState.value = VideosUiState.Success(updatedVideos)
        }

        viewModelScope.launch {
            try {
                if (isLiked) {
                    videoRepository.unlikeVideo(videoId)
                        .onFailure {
                            // Revert on failure
                            revertLikeState(videoId, true)
                        }
                } else {
                    videoRepository.likeVideo(videoId)
                        .onFailure {
                            // Revert on failure
                            revertLikeState(videoId, false)
                        }
                }
            } finally {
                likeRequestsInFlight.remove(videoId)
            }
        }
    }

    private fun revertLikeState(videoId: String, wasLiked: Boolean) {
         val currentState = _videosState.value
         if (currentState is VideosUiState.Success) {
             val updatedVideos = currentState.videos.map { video ->
                 if (video.id == videoId) {
                     val newLikeCount = maxOf(0, if (wasLiked) video.likeCount + 1 else video.likeCount - 1)
                     video.copy(likedByMe = wasLiked, likeCount = newLikeCount)
                 } else video
             }
             _videosState.value = VideosUiState.Success(updatedVideos)
         }
    }

    // Comments State
    private var commentsJob: kotlinx.coroutines.Job? = null
    private val _selectedVideoForComments = MutableStateFlow<String?>(null)
    val selectedVideoForComments: StateFlow<String?> = _selectedVideoForComments.asStateFlow()

    private val _commentsState = MutableStateFlow<List<conectate.com.data.model.VideoComment>>(emptyList())
    val commentsState: StateFlow<List<conectate.com.data.model.VideoComment>> = _commentsState.asStateFlow()

    private val _mentionSuggestions = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val mentionSuggestions: StateFlow<List<conectate.com.data.model.User>> = _mentionSuggestions.asStateFlow()
    private val _reportedVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val reportedVideoIds: StateFlow<Set<String>> = _reportedVideoIds.asStateFlow()
    private val _reportActionMessage = MutableStateFlow<String?>(null)
    val reportActionMessage: StateFlow<String?> = _reportActionMessage.asStateFlow()
    private var mentionSearchJob: Job? = null

    fun openComments(videoId: String) {
        _selectedVideoForComments.value = videoId
        loadComments(videoId)
    }

    fun closeComments() {
        commentsJob?.cancel()
        _selectedVideoForComments.value = null
        _commentsState.value = emptyList()
        clearMentionSuggestions()
        stopAudio()
        voiceMessageManager.reset()
    }

    private fun loadComments(videoId: String) {
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            videoRepository.getCommentsFlow(videoId).collect { comments ->
                _commentsState.value = comments
            }
        }
    }

    // Audio Comment State (Delegated to VoiceMessageManager)
    val voiceState = voiceMessageManager.state
    val recordingDuration = voiceMessageManager.duration
    val isDraftPlaying = voiceMessageManager.isDraftPlaying
    val draftProgress = voiceMessageManager.playbackProgress
    val draftDuration = voiceMessageManager.playbackDurationLabel

    private var mediaPlayer: MediaPlayer? = null
    private val _currentPlayingCommentId = MutableStateFlow<String?>(null)
    val currentPlayingCommentId: StateFlow<String?> = _currentPlayingCommentId.asStateFlow()

    private val _audioProgress = MutableStateFlow(0f)
    val audioProgress: StateFlow<Float> = _audioProgress.asStateFlow()

    private var progressJob: Job? = null

    fun startRecording() {
        voiceMessageManager.startRecording()
    }
    
    fun pauseRecording() {
        voiceMessageManager.pauseRecording()
    }
    
    fun resumeRecording() {
        val state = voiceState.value
        if (state == conectate.com.presentation.messages.VoiceState.PAUSED) {
            voiceMessageManager.resumeRecording()
        } else if (state == conectate.com.presentation.messages.VoiceState.REVIEWING) {
            voiceMessageManager.resumeRecordingFromReview()
        }
    }
    
    fun cancelRecording() {
        voiceMessageManager.cancelRecording()
    }
    
    fun playDraft() = voiceMessageManager.playPreview()
    fun pauseDraft() = voiceMessageManager.pausePreview()
    fun seekDraft(fraction: Float) = voiceMessageManager.seekPreview(fraction)

    fun stopRecording(shouldSend: Boolean) {
        if (!shouldSend) {
            voiceMessageManager.stopRecordingForReview()
        } else {
            val currentState = voiceState.value
            var fileToSend: java.io.File? = null
            var duration = 0L
            
            if (currentState == conectate.com.presentation.messages.VoiceState.RECORDING || currentState == conectate.com.presentation.messages.VoiceState.PAUSED) {
                val pair = voiceMessageManager.stopAndGetFile()
                fileToSend = pair.first
                duration = pair.second
            } else if (currentState == conectate.com.presentation.messages.VoiceState.REVIEWING) {
                fileToSend = voiceMessageManager.getRecordedFile()
                duration = recordingDuration.value
                voiceMessageManager.reset()
            }
            
            if (fileToSend != null && duration > 800) {
                 val videoId = _selectedVideoForComments.value ?: return
                 viewModelScope.launch {
                     videoRepository.sendAudioComment(videoId, fileToSend)
                 }
            } else {
                voiceMessageManager.reset()
            }
        }
    }

    fun playAudio(comment: conectate.com.data.model.VideoComment) {
        if (_currentPlayingCommentId.value == comment.id) {
            stopAudio()
            return
        }

        stopAudio()
        
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(comment.mediaUrl)
                prepareAsync()
                setOnPreparedListener { 
                    start()
                    _currentPlayingCommentId.value = comment.id
                    startProgressUpdate()
                }
                setOnCompletionListener { 
                    stopAudio()
                }
                setOnErrorListener { _, _, _ ->
                    stopAudio()
                    true
                }
            } catch (e: Exception) {
                android.util.Log.e("VideosViewModel", "Error playing audio", e)
                stopAudio()
            }
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (mediaPlayer?.isPlaying == true) {
                val progress = mediaPlayer?.let { it.currentPosition.toFloat() / it.duration } ?: 0f
                _audioProgress.value = progress
                delay(50)
            }
        }
    }

    private fun stopAudio() {
        _currentPlayingCommentId.value = null
        _audioProgress.value = 0f
        progressJob?.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
        commentsJob?.cancel()
    }

    private val _replyingTo = MutableStateFlow<conectate.com.data.model.VideoComment?>(null)
    val replyingTo: StateFlow<conectate.com.data.model.VideoComment?> = _replyingTo.asStateFlow()

    fun onReplyClick(comment: conectate.com.data.model.VideoComment) {
        _replyingTo.value = comment
    }

    fun cancelReply() {
        _replyingTo.value = null
    }

    fun addComment(content: String) {
        android.util.Log.d("VideosViewModel", "addComment called with content: $content")
        val videoId = _selectedVideoForComments.value ?: run {
            android.util.Log.e("VideosViewModel", "No video selected for comments")
            return
        }
        if (content.isBlank()) {
            android.util.Log.e("VideosViewModel", "Content is blank")
            return
        }

        val parentId = _replyingTo.value?.id
        android.util.Log.d("VideosViewModel", "Launching coroutine to add comment for video: $videoId, parentId: $parentId")
        viewModelScope.launch {
            videoRepository.addComment(videoId, content, parentId = parentId).onFailure { e ->
                android.util.Log.e("VideosViewModel", "Error adding comment", e)
            }.onSuccess {
                android.util.Log.d("VideosViewModel", "Comment added successfully")
                cancelReply()
                clearMentionSuggestions()
                loadComments(videoId)
                reloadVideo(videoId)
            }
        }
    }

    fun onCommentDraftChanged(text: String) {
        val query = extractActiveMentionQuery(text)
        if (query.isNullOrBlank()) {
            clearMentionSuggestions()
            mentionSearchJob?.cancel()
            return
        }

        mentionSearchJob?.cancel()
        mentionSearchJob = viewModelScope.launch {
            userRepository.searchMentionCandidates(
                query = query,
                excludeUserId = authService.getCurrentUserId(),
                limit = 6
            ).onSuccess { users ->
                _mentionSuggestions.value = users
            }.onFailure {
                _mentionSuggestions.value = emptyList()
            }
        }
    }

    fun clearMentionSuggestions() {
        _mentionSuggestions.value = emptyList()
    }

    fun consumeReportActionMessage() {
        _reportActionMessage.value = null
    }

    fun ensureVideoReportStatus(videoId: String) {
        if (_reportedVideoIds.value.contains(videoId)) return
        viewModelScope.launch {
            videoRepository.hasReportedVideo(videoId).onSuccess { hasReported ->
                if (hasReported) {
                    _reportedVideoIds.value = _reportedVideoIds.value + videoId
                }
            }
        }
    }

    fun submitVideoReport(videoId: String, reason: String, details: String? = null) {
        if (_reportedVideoIds.value.contains(videoId)) {
            _reportActionMessage.value = "Ya reportaste este video"
            return
        }
        if (!reportRequestsInFlight.add(videoId)) return

        viewModelScope.launch {
            try {
                videoRepository.submitVideoReport(videoId, reason, details).onSuccess {
                    _reportedVideoIds.value = _reportedVideoIds.value + videoId
                    _reportActionMessage.value = "Reporte enviado"
                }.onFailure { error ->
                    val message = error.message ?: "No se pudo enviar el reporte"
                    if (message.contains("Ya reportaste", ignoreCase = true)) {
                        _reportedVideoIds.value = _reportedVideoIds.value + videoId
                    }
                    _reportActionMessage.value = message
                }
            } finally {
                reportRequestsInFlight.remove(videoId)
            }
        }
    }

    private fun reloadVideo(videoId: String) {
        viewModelScope.launch {
            delay(300)
            videoRepository.getVideo(videoId).onSuccess { updatedVideo ->
                val currentState = _videosState.value
                if (currentState is VideosUiState.Success) {
                     val updatedList = currentState.videos.map { if (it.id == videoId) updatedVideo else it }
                     _videosState.value = VideosUiState.Success(updatedList)
                }
            }.onFailure {
                android.util.Log.e("VideosViewModel", "Failed to reload video", it)
            }
        }
    }

    fun toggleVideoShare(video: Video) {
        if (!shareRequestsInFlight.add(video.id)) return
        viewModelScope.launch {
            try {
                val userId = authService.getCurrentUserId() ?: return@launch
                val wasShared = video.sharedByMe
                val delta = if (wasShared) -1 else 1
                
                // Optimistic Update
                val currentState = _videosState.value
                if (currentState is VideosUiState.Success) {
                    val updatedVideos = currentState.videos.map { v ->
                        if (v.id == video.id) {
                            val newShareCount = if (wasShared) maxOf(0, v.shareCount - 1) else v.shareCount + 1
                            v.copy(sharedByMe = !wasShared, shareCount = newShareCount)
                        } else v
                    }
                    _videosState.value = VideosUiState.Success(updatedVideos)
                }

                val shareResult = if (wasShared) {
                    shareRepository.unshareContent(userId, "video", video.id)
                } else {
                    shareRepository.shareContent(userId, "video", video.id)
                }

                if (shareResult.isFailure) {
                    // Revert on failure
                    val failedState = _videosState.value
                    if (failedState is VideosUiState.Success) {
                        val revertedVideos = failedState.videos.map { v ->
                            if (v.id == video.id) {
                                val restoredShareCount = if (wasShared) v.shareCount + 1 else maxOf(0, v.shareCount - 1)
                                v.copy(sharedByMe = wasShared, shareCount = restoredShareCount)
                            } else v
                        }
                        _videosState.value = VideosUiState.Success(revertedVideos)
                    }
                    return@launch
                }

                val countResult = videoRepository.incrementShareCount(video.id, delta)
                if (countResult.isFailure) {
                    val failedState = _videosState.value
                    if (failedState is VideosUiState.Success) {
                        val revertedVideos = failedState.videos.map { v ->
                            if (v.id == video.id) {
                                val restoredShareCount = if (wasShared) v.shareCount + 1 else maxOf(0, v.shareCount - 1)
                                v.copy(sharedByMe = wasShared, shareCount = restoredShareCount)
                            } else v
                        }
                        _videosState.value = VideosUiState.Success(revertedVideos)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VideosViewModel", "Error toggling video share", e)
            } finally {
                shareRequestsInFlight.remove(video.id)
            }
        }
    }

    private fun extractActiveMentionQuery(text: String): String? {
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0 || atIndex == text.lastIndex) return null

        val mentionCandidate = text.substring(atIndex + 1)
        if (mentionCandidate.any { it.isWhitespace() }) return null
        if (mentionCandidate.length > 30) return null

        return mentionCandidate.trim()
    }

    private fun saveVideosCache(videos: List<Video>) {
        runCatching {
            val trimmed = videos.take(5)
            val encoded = json.encodeToString(ListSerializer(Video.serializer()), trimmed)
            context.getSharedPreferences(cachePrefsName, Context.MODE_PRIVATE)
                .edit()
                .putString(cacheKey, encoded)
                .apply()
        }
    }

    private fun readVideosCache(): List<Video> {
        return runCatching {
            val raw = context.getSharedPreferences(cachePrefsName, Context.MODE_PRIVATE)
                .getString(cacheKey, null)
                .orEmpty()
            if (raw.isBlank()) emptyList()
            else json.decodeFromString(ListSerializer(Video.serializer()), raw)
        }.getOrDefault(emptyList())
    }
}
