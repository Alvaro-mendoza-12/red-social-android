package conectate.com.presentation.videos

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen
import conectate.com.data.repository.AuthRepository
import conectate.com.data.model.Video
import conectate.com.data.model.VideoComment
import conectate.com.data.model.videoChapterTimeLabel
import conectate.com.data.model.parseVideoChapterTime
import conectate.com.data.model.validVideoClipRange
import conectate.com.data.model.videoClipLink
import conectate.com.data.repository.VideoRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.ShareRepository
import conectate.com.data.local.prefs.AppSettingsPreferences
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.common.ConnectivityViewModel
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.common.toUserFacingMessage
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.OfflineNoticeBar
import conectate.com.presentation.video.RealPlaybackViewTracker
import conectate.com.presentation.video.VideoPlaybackRecoveryDecision
import conectate.com.presentation.video.VideoPlaybackRecoveryRunner
import conectate.com.presentation.video.VideoHistoryViewModel
import conectate.com.presentation.video.decideVideoPlaybackRecovery
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.util.AudioRecorder
import conectate.com.util.UbuntuMedia
import conectate.com.util.VideoCache
import conectate.com.util.VideoPerformanceProfiles
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import android.media.MediaPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import androidx.compose.material.icons.automirrored.filled.Comment

sealed class VideoPlayerUiState {
    object Loading : VideoPlayerUiState()
    data class Success(
        val video: Video,
        val comments: List<VideoComment>,
        val isLiked: Boolean
    ) : VideoPlayerUiState()
    data class Error(val message: String) : VideoPlayerUiState()
}

@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val videoRepository: VideoRepository,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val voiceMessageManager: conectate.com.presentation.messages.VoiceMessageManager,
    private val shareRepository: ShareRepository,
    appSettingsPreferences: AppSettingsPreferences,
) : ViewModel() {

    val videoAutoplayOnMobile = appSettingsPreferences.videoAutoplayOnMobile

    private val videoId: String = checkNotNull(savedStateHandle["videoId"])

    private val _uiState = MutableStateFlow<VideoPlayerUiState>(VideoPlayerUiState.Loading)
    val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

    private val _commentText = MutableStateFlow("")
    val commentText: StateFlow<String> = _commentText.asStateFlow()
    private val _replyingTo = MutableStateFlow<VideoComment?>(null)
    val replyingTo: StateFlow<VideoComment?> = _replyingTo.asStateFlow()
    private val _mentionSuggestions = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val mentionSuggestions: StateFlow<List<conectate.com.data.model.User>> = _mentionSuggestions.asStateFlow()
    private val _reportedVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val reportedVideoIds: StateFlow<Set<String>> = _reportedVideoIds.asStateFlow()
    private val _reportActionMessage = MutableStateFlow<String?>(null)
    val reportActionMessage: StateFlow<String?> = _reportActionMessage.asStateFlow()

    // Real-time Stats
    private val _likeCount = MutableStateFlow(0)
    val likeCount: StateFlow<Int> = _likeCount.asStateFlow()

    private val _isLiked = MutableStateFlow(false)
    val isLiked: StateFlow<Boolean> = _isLiked.asStateFlow()

    private val _commentCount = MutableStateFlow(0)
    val commentCount: StateFlow<Int> = _commentCount.asStateFlow()

    private val _viewCount = MutableStateFlow(0)
    val viewCount: StateFlow<Int> = _viewCount.asStateFlow()

    private val _shareCount = MutableStateFlow(0)
    val shareCount: StateFlow<Int> = _shareCount.asStateFlow()

    // Voice Manager State Expose
    val voiceState = voiceMessageManager.state
    val recordingDuration = voiceMessageManager.duration
    val voicePlaybackProgress = voiceMessageManager.playbackProgress
    val voicePlaybackDurationLabel = voiceMessageManager.playbackDurationLabel
    val isDraftPlaying = voiceMessageManager.isDraftPlaying

    private var progressJob: Job? = null
    private var commentsSubscriptionJob: Job? = null
    private var statsJob: Job? = null
    private var mentionSearchJob: Job? = null
    private val playbackViewTracker = RealPlaybackViewTracker()
    private val likeRequestInFlight = MutableStateFlow(false)
    private val shareRequestInFlight = MutableStateFlow(false)
    private val reportRequestsInFlight = mutableSetOf<String>()

    val currentUserId: String? = authRepository.getCurrentUserId()
    val userRepo: UserRepository get() = userRepository

    init {
        loadVideo()
        observeStats()
    }

    private fun loadVideo() {
        viewModelScope.launch {
            try {
                // Load video and comments
                val videoResult = videoRepository.getVideoById(videoId)
                videoResult.fold(
                    onSuccess = { video ->
                        if (!isCurrentPlaybackAccount()) return@fold
                        _isLiked.value = video.likedByMe
                        // Comments are optional for playback. Publish the
                        // media immediately before their independent lookup.
                        _uiState.value = VideoPlayerUiState.Success(video, emptyList(), video.likedByMe)
                        // Counts are handled by observeStats() to ensure single source of truth (Room + Realtime)
                        // _likeCount.value = video.likeCount
                        // _commentCount.value = video.commentCount
                        // _viewCount.value = video.views

                        commentsSubscriptionJob?.cancel()
                        refreshComments(video)
                        observeComments()
                    },
                    onFailure = { error ->
                        if (!isCurrentPlaybackAccount()) return@fold
                        _uiState.value = VideoPlayerUiState.Error(
                            error.toUserFacingMessage("Este video ya no está disponible.")
                        )
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (isCurrentPlaybackAccount() && _uiState.value !is VideoPlayerUiState.Success) {
                    _uiState.value = VideoPlayerUiState.Error(
                        e.toUserFacingMessage("Este video ya no está disponible.")
                    )
                }
            }
        }
    }

    fun isCurrentPlaybackAccount(): Boolean = currentUserId == authRepository.getCurrentUserId()

    suspend fun refreshPlaybackVideo(): Video? {
        if (!isCurrentPlaybackAccount()) return null
        return videoRepository.getVideo(videoId).getOrNull()?.takeIf { isCurrentPlaybackAccount() }
    }

    fun updatePlaybackUrl(videoUrl: String) {
        if (!isCurrentPlaybackAccount()) return
        val state = _uiState.value as? VideoPlayerUiState.Success ?: return
        _uiState.value = state.copy(video = state.video.copy(videoUrl = videoUrl))
    }

    private fun observeStats() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            videoRepository.getVideoStatsFlow(videoId).collect { stats ->
                if (stats != null) {
                    _likeCount.value = stats.likeCount
                    _commentCount.value = stats.commentCount
                    _viewCount.value = stats.viewCount
                    _shareCount.value = stats.shareCount
                }
            }
        }
    }

    fun toggleLike() {
        if (likeRequestInFlight.value) return
        viewModelScope.launch {
            likeRequestInFlight.value = true
            try {
                val currentlyLiked = _isLiked.value

                // Optimistic Update
                _isLiked.value = !currentlyLiked
                _likeCount.value += if (currentlyLiked) -1 else 1

                val result = if (currentlyLiked) {
                    videoRepository.unlikeVideo(videoId)
                } else {
                    videoRepository.likeVideo(videoId)
                }
                if (result.isFailure) {
                    _isLiked.value = currentlyLiked
                    _likeCount.value += if (currentlyLiked) 1 else -1
                }
            } finally {
                likeRequestInFlight.value = false
            }
        }
    }

    fun updateCommentText(text: String) {
        _commentText.value = text
        onCommentDraftChanged(text)
    }

    fun postComment() {
        val text = _commentText.value.trim()
        android.util.Log.d("VideoPlayerVM", "postComment called with text: '$text'")
        val parentId = _replyingTo.value?.id
        
        if (text.isEmpty()) {
            android.util.Log.w("VideoPlayerVM", "Text is empty, returning")
            return
        }

        android.util.Log.d("VideoPlayerVM", "Launching coroutine to add comment for videoId: $videoId, parentId: $parentId")
        viewModelScope.launch {
            videoRepository.addComment(videoId, text, parentId = parentId).onSuccess { insertedComment ->
                android.util.Log.d("VideoPlayerVM", "Comment added successfully, clearing text")
                val currentState = _uiState.value
                if (currentState is VideoPlayerUiState.Success) {
                    val nextComments = mergeComments(currentState.comments, listOf(insertedComment))
                    _uiState.value = currentState.copy(comments = nextComments)
                    _commentCount.value = nextComments.size
                }
                _commentText.value = ""
                _replyingTo.value = null
                clearMentionSuggestions()
                refreshCommentsAfterWrite()
            }.onFailure { e ->
                android.util.Log.e("VideoPlayerVM", "Error sending comment", e)
            }
        }
    }

    fun onVideoPlaybackSample(isActuallyPlaying: Boolean, elapsedRealtimeMs: Long) {
        if (!playbackViewTracker.sample(videoId, isActuallyPlaying, elapsedRealtimeMs)) return

        viewModelScope.launch {
            videoRepository.incrementViews(videoId)
                .onFailure { error ->
                    playbackViewTracker.allowRetry(videoId)
                    android.util.Log.e(
                        "VideoPlayerVM",
                        "Failed to increment video views for $videoId",
                        error
                    )
                }
        }
    }

    private fun refreshCommentsAfterWrite() {
        viewModelScope.launch {
            delay(250)
            val currentVideo = (_uiState.value as? VideoPlayerUiState.Success)?.video
            refreshComments(currentVideo)
        }
    }

    private suspend fun refreshComments(video: Video?) {
        val loaded = videoRepository.loadVideoCommentsNow(videoId)
            .onFailure { error ->
                android.util.Log.e("VideoPlayerVM", "Failed to refresh comments", error)
            }
            .getOrNull()
            ?: emptyList()
        if (!isCurrentPlaybackAccount()) return
        val currentState = _uiState.value
        val nextComments = loaded
        if (currentState is VideoPlayerUiState.Success) {
            _uiState.value = currentState.copy(comments = nextComments)
        } else if (video != null) {
            _uiState.value = VideoPlayerUiState.Success(
                video = video,
                comments = nextComments,
                isLiked = _isLiked.value
            )
        }
        _commentCount.value = nextComments.size
    }

    private fun observeComments() {
        commentsSubscriptionJob?.cancel()
        commentsSubscriptionJob = viewModelScope.launch {
            videoRepository.getCommentsFlow(videoId).collect { comments ->
                val currentState = _uiState.value as? VideoPlayerUiState.Success ?: return@collect
                _uiState.value = currentState.copy(comments = comments)
                _commentCount.value = comments.size
            }
        }
    }

    private fun mergeComments(
        current: List<VideoComment>,
        incoming: List<VideoComment>
    ): List<VideoComment> {
        return mergeLatestVideoComments(current, incoming)
    }

    fun onReplyClick(comment: VideoComment) {
        _replyingTo.value = comment
    }

    fun cancelReply() {
        _replyingTo.value = null
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
                excludeUserId = currentUserId,
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

    // Audio playback for received comments (separate from recording)
    private var mediaPlayer: MediaPlayer? = null
    private val _currentPlayingCommentId = MutableStateFlow<String?>(null)
    val currentPlayingCommentId: StateFlow<String?> = _currentPlayingCommentId.asStateFlow()

    private val _audioProgress = MutableStateFlow(0f)
    val audioProgress: StateFlow<Float> = _audioProgress.asStateFlow()

    fun playAudio(comment: VideoComment) {
        if (_currentPlayingCommentId.value == comment.id) {
            stopAudio()
            return
        }

        stopAudio()
        
        mediaPlayer = MediaPlayer().apply {
            try {
                val audioUrl = comment.mediaUrl ?: return
                UbuntuMedia.setDataSource(this, audioUrl)
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
                android.util.Log.e("VideoPlayerVM", "Error playing audio", e)
                stopAudio()
            }
        }
    }


    // Voice Message Recording - delegated to VoiceMessageManager
    fun startRecording() = voiceMessageManager.startRecording()
    fun pauseRecording() = voiceMessageManager.pauseRecording()
    fun resumeRecording() = voiceMessageManager.resumeRecording()
    fun resumeRecordingFromReview() = voiceMessageManager.resumeRecordingFromReview()
    fun cancelRecording() = voiceMessageManager.cancelRecording()
    
    fun stopRecording(sendDirectly: Boolean) {
        if (sendDirectly) {
            // Quick send
            voiceMessageManager.stopRecording()
            sendCurrentRecording()
        } else {
            // Go to review mode
            voiceMessageManager.stopRecording()
        }
    }
    
    fun sendCurrentRecording() {
        viewModelScope.launch {
            val file = voiceMessageManager.getRecordedFile()
            if (file != null && file.exists()) {
                videoRepository.sendAudioComment(videoId, file)
                    .onSuccess { refreshCommentsAfterWrite() }
                voiceMessageManager.clearRecording()
            }
        }
    }
    
    // Preview playback controls
    fun playDraftPreview() = voiceMessageManager.playPreview()
    fun pauseDraftPreview() = voiceMessageManager.pausePreview()
    fun seekDraftPreview(fraction: Float) = voiceMessageManager.seekPreview(fraction)

    fun deleteComment(comment: VideoComment) {
        val currentState = _uiState.value as? VideoPlayerUiState.Success ?: return
        val previousComments = currentState.comments
        val nextComments = removeVideoCommentImmediately(previousComments, comment.id)
        _uiState.value = currentState.copy(comments = nextComments)
        _commentCount.value = nextComments.size

        viewModelScope.launch {
            videoRepository.deleteComment(comment.id).onSuccess {
                delay(250)
                refreshComments(currentState.video)
            }.onFailure { error ->
                android.util.Log.e("VideoPlayerVM", "Error deleting comment", error)
                val latestState = _uiState.value
                if (latestState is VideoPlayerUiState.Success) {
                    val restored = mergeComments(latestState.comments, previousComments)
                    _uiState.value = latestState.copy(comments = restored)
                    _commentCount.value = restored.size
                }
            }
        }
    }

    fun reactToComment(comment: VideoComment, reaction: String?) {
        val currentState = _uiState.value as? VideoPlayerUiState.Success ?: return
        val previousComments = currentState.comments
        _uiState.value = currentState.copy(
            comments = reactToVideoCommentImmediately(previousComments, comment.id, reaction)
        )
        viewModelScope.launch {
            videoRepository.setCommentReaction(comment.id, reaction)
                .onSuccess { refreshCommentsAfterWrite() }
                .onFailure {
                    val latestState = _uiState.value as? VideoPlayerUiState.Success
                    if (latestState != null) {
                        _uiState.value = latestState.copy(comments = previousComments)
                    }
                    _reportActionMessage.value = it.message ?: "No se pudo reaccionar"
                }
        }
    }

    fun editComment(comment: VideoComment, content: String) {
        val currentState = _uiState.value as? VideoPlayerUiState.Success ?: return
        val previousComments = currentState.comments
        _uiState.value = currentState.copy(
            comments = editVideoCommentImmediately(previousComments, comment.id, content)
        )
        viewModelScope.launch {
            videoRepository.editComment(comment.id, content)
                .onSuccess { refreshCommentsAfterWrite() }
                .onFailure {
                    val latestState = _uiState.value as? VideoPlayerUiState.Success
                    if (latestState != null) {
                        _uiState.value = latestState.copy(comments = previousComments)
                    }
                    _reportActionMessage.value = it.message ?: "No se pudo editar"
                }
        }
    }

    fun reportComment(comment: VideoComment) {
        viewModelScope.launch {
            videoRepository.reportComment(comment.id, "Contenido inapropiado")
                .onSuccess { _reportActionMessage.value = "Comentario reportado" }
                .onFailure { _reportActionMessage.value = it.message ?: "No se pudo reportar" }
        }
    }

    fun addStickerComment(url: String, label: String) {
        viewModelScope.launch {
            videoRepository.addStickerComment(videoId, url, label)
                .onSuccess { insertedComment ->
                    val currentState = _uiState.value as? VideoPlayerUiState.Success
                    if (currentState != null) {
                        val nextComments = mergeLatestVideoComments(
                            currentState.comments,
                            listOf(insertedComment)
                        )
                        _uiState.value = currentState.copy(comments = nextComments)
                        _commentCount.value = nextComments.size
                    }
                    refreshCommentsAfterWrite()
                }
                .onFailure { _reportActionMessage.value = it.message ?: "No se pudo enviar el sticker" }
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
        commentsSubscriptionJob?.cancel()
    }

    private fun extractActiveMentionQuery(text: String): String? {
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0 || atIndex == text.lastIndex) return null

        val mentionCandidate = text.substring(atIndex + 1)
        if (mentionCandidate.any { it.isWhitespace() }) return null
        if (mentionCandidate.length > 30) return null

        return mentionCandidate.trim()
    }
    
    fun deleteVideo(onSuccess: () -> Unit) {
        viewModelScope.launch {
            videoRepository.deleteVideo(videoId).onSuccess {
                 onSuccess()
            }
        }
    }

    fun updateVideo(title: String, description: String, isPublic: Boolean,
                    allowClipSharing: Boolean, onSuccess: () -> Unit) {
        viewModelScope.launch {
            videoRepository.updateVideo(videoId, title, description, isPublic,
                allowClipSharing).onSuccess {
                 onSuccess()
                 loadVideo() // Reload to reflect changes
            }.onFailure { error ->
                AppFeedbackBus.show(error.toUserFacingMessage("No se pudo guardar el video."))
            }
        }
    }
    
    fun isOwner(userId: String): Boolean {
        // Simple check against auth service
        return authRepository.getCurrentUserId() == userId
    }

    fun toggleShare() {
        if (shareRequestInFlight.value) return
        viewModelScope.launch {
            shareRequestInFlight.value = true
            try {
                val currentState = _uiState.value
                if (currentState is VideoPlayerUiState.Success) {
                    val video = currentState.video
                    authRepository.getCurrentUserId() ?: return@launch
                    val wasShared = video.sharedByMe

                    // Optimistic Update
                    val newSharedState = !wasShared
                    val newShareCount = if (newSharedState) (shareCount.value + 1) else maxOf(0, shareCount.value - 1)

                    _uiState.value = currentState.copy(video = video.copy(sharedByMe = newSharedState))
                    _shareCount.value = newShareCount

                    val countResult = videoRepository.incrementShareCount(
                        videoId = videoId,
                        delta = if (newSharedState) 1 else -1
                    )
                    if (countResult.isFailure) {
                        // Revert on failure
                        _uiState.value = currentState.copy(video = video.copy(sharedByMe = wasShared))
                        _shareCount.value = if (wasShared) (newShareCount + 1) else maxOf(0, newShareCount - 1)
                    } else {
                        videoRepository.getVideoById(videoId).onSuccess { refreshed ->
                            val latestState = _uiState.value
                            if (latestState is VideoPlayerUiState.Success) {
                                val merged = refreshed.copy(
                                    views = maxOf(latestState.video.views, refreshed.views),
                                    likeCount = refreshed.likeCount.coerceAtLeast(0),
                                    commentCount = refreshed.commentCount.coerceAtLeast(0),
                                    shareCount = refreshed.shareCount.coerceAtLeast(0),
                                    likedByMe = latestState.video.likedByMe || refreshed.likedByMe,
                                    sharedByMe = newSharedState
                                )
                                _uiState.value = latestState.copy(video = merged)
                                _shareCount.value = merged.shareCount
                            }
                        }
                    }
                }
            } finally {
                shareRequestInFlight.value = false
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    navController: NavController,
    focusCommentId: String? = null,
    initialPositionMs: Long = 0L,
    initialClipEndMs: Long = 0L,
    viewModel: VideoPlayerViewModel = hiltViewModel(),
    connectivityViewModel: ConnectivityViewModel = hiltViewModel()
) {
    val historyViewModel: VideoHistoryViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val commentText by viewModel.commentText.collectAsState()
    val replyingTo by viewModel.replyingTo.collectAsState()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsState()
    val reportedVideoIds by viewModel.reportedVideoIds.collectAsState()
    val reportActionMessage by viewModel.reportActionMessage.collectAsState()
    val currentPlayingCommentId by viewModel.currentPlayingCommentId.collectAsState()
    val audioProgress by viewModel.audioProgress.collectAsState()
    
    // Voice Manager States
    val voiceState by viewModel.voiceState.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val voicePlaybackProgress by viewModel.voicePlaybackProgress.collectAsState()
    val voicePlaybackDurationLabel by viewModel.voicePlaybackDurationLabel.collectAsState()
    val isDraftPlaying by viewModel.isDraftPlaying.collectAsState()
    
    // Real-time states
    val isLiked by viewModel.isLiked.collectAsState()
    val likeCount by viewModel.likeCount.collectAsState()
    val viewCount by viewModel.viewCount.collectAsState()
    val commentCount by viewModel.commentCount.collectAsState()
    val shareCount by viewModel.shareCount.collectAsState()
    val isConnected by connectivityViewModel.isConnected.collectAsState()
    val isCellular by connectivityViewModel.isCellular.collectAsState()
    val videoAutoplayOnMobile by viewModel.videoAutoplayOnMobile.collectAsState()
    val cellularAutoplayBlocked = isCellular && !videoAutoplayOnMobile
    
    var showInternalShareDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showStickerPanel by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var focusResolved by remember(focusCommentId) { mutableStateOf(focusCommentId.isNullOrBlank()) }
    var commentOrder by remember { mutableStateOf(VideoCommentOrder.RECENT) }
    var commentAuthorFilter by remember { mutableStateOf(VideoCommentAuthorFilter.ALL) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var lifecycleResumed by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        )
    }
    var playbackRequested by remember { mutableStateOf(!cellularAutoplayBlocked) }
    val currentVideo = (uiState as? VideoPlayerUiState.Success)?.video
    var mediaDurationMs by remember(currentVideo?.id) { mutableLongStateOf(0L) }
    val linkedClip = currentVideo?.takeIf { it.isPublic && it.allowClipSharing }
        ?.let { validVideoClipRange(initialPositionMs, initialClipEndMs,
            mediaDurationMs.takeIf { duration -> duration > 0L } ?:
                (it.duration.takeIf { seconds -> seconds in 1L..86_400L } ?: 0L) * 1_000L) }
    val latestLinkedClip = rememberUpdatedState(linkedClip)
    var rejectedClipLink by remember(currentVideo?.id) { mutableStateOf(false) }
    var initialClipApplied by remember(currentVideo?.id) { mutableStateOf(false) }
    var showClipComposer by remember(currentVideo?.id) { mutableStateOf(false) }
    var clipStartText by remember(currentVideo?.id) { mutableStateOf("") }
    var clipEndText by remember(currentVideo?.id) { mutableStateOf("") }

    LaunchedEffect(currentVideo?.id, cellularAutoplayBlocked) {
        if (cellularAutoplayBlocked) playbackRequested = false
    }
    var automaticPlaybackAttempts by remember(currentVideo?.id) { mutableIntStateOf(0) }
    var playbackError by remember(currentVideo?.id) { mutableStateOf(false) }
    var playbackReloadSequence by remember(currentVideo?.id) { mutableIntStateOf(0) }
    var lastLoadedMediaKey by remember(currentVideo?.id) { mutableStateOf<String?>(null) }
    val playbackScope = rememberCoroutineScope()
    val recoveryRunner = remember(playbackScope) { VideoPlaybackRecoveryRunner(playbackScope) }
    val latestVideo = rememberUpdatedState(currentVideo)
    DisposableEffect(recoveryRunner, currentVideo?.id) {
        onDispose { recoveryRunner.cancel() }
    }
    val playbackGateOpen = lifecycleResumed &&
        !showInternalShareDialog &&
        !showReportDialog &&
        currentVideo != null
    val playbackGateOpenRef = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    SideEffect { playbackGateOpenRef.set(playbackGateOpen) }

    LaunchedEffect(reportActionMessage) {
        val message = reportActionMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        viewModel.consumeReportActionMessage()
    }

    val exoPlayer = remember(context) {
        val performanceProfile = VideoPerformanceProfiles.detect(context)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                performanceProfile.minBufferMs,
                performanceProfile.maxBufferMs,
                performanceProfile.playBufferMs,
                performanceProfile.rebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    VideoCache.getCacheDataSourceFactory(context)
                )
            )
            .build().apply {
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            playWhenReady = false
        }
    }

    fun recoverPlayback(delayMs: Long, refreshEndpoint: Boolean, evictCache: Boolean = false) {
        val videoId = latestVideo.value?.id ?: return
        val failedUrl = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
        playbackError = false
        recoveryRunner.schedule(
            delayMs = delayMs,
            isCurrent = {
                latestVideo.value?.id == videoId && viewModel.isCurrentPlaybackAccount() &&
                    exoPlayer.currentMediaItem?.mediaId == videoId
            },
            resolve = {
                if (evictCache) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        VideoCache.removeResource(context.applicationContext, failedUrl)
                    }
                }
                if (refreshEndpoint) RuntimeBackendEndpoint.refreshAfterEndpointFailure(failedUrl)
                val refreshed = if (refreshEndpoint) viewModel.refreshPlaybackVideo() else null
                refreshed ?: latestVideo.value
            },
            apply = { video ->
                viewModel.updatePlaybackUrl(video.videoUrl)
                playbackReloadSequence += 1
            },
            onFailure = { playbackError = true },
        )
    }

    LaunchedEffect(currentVideo?.id, mediaDurationMs, linkedClip) {
        if (!initialClipApplied && linkedClip != null && mediaDurationMs > 0L) {
            initialClipApplied = true
            exoPlayer.seekTo(linkedClip.startMs)
        }
        if (!rejectedClipLink && currentVideo != null && initialClipEndMs > 0L &&
            mediaDurationMs > 0L && linkedClip == null) {
            rejectedClipLink = true
            exoPlayer.seekTo(0L)
            AppFeedbackBus.show("Este fragmento ya no está permitido o quedó fuera del video.")
        }
    }

    LaunchedEffect(currentVideo?.id, currentVideo?.videoUrl, playbackReloadSequence) {
        val video = currentVideo ?: return@LaunchedEffect
        val resolvedUrl = UbuntuMedia.resolveUrl(video.videoUrl) ?: video.videoUrl
        val mediaKey = "${video.id}|$resolvedUrl|$playbackReloadSequence"
        if (mediaKey == lastLoadedMediaKey) return@LaunchedEffect

        val resumePositionMs = exoPlayer.currentMediaItem
            ?.takeIf { it.mediaId == video.id }
            ?.let { exoPlayer.currentPosition.coerceAtLeast(0L) }
            ?: (if (initialClipEndMs > 0L && linkedClip == null) 0L
                else initialPositionMs.coerceAtLeast(0L))
        exoPlayer.setMediaItem(UbuntuMedia.mediaItem(video.videoUrl, mediaId = video.id), resumePositionMs)
        exoPlayer.prepare()
        if (playbackGateOpen && playbackRequested) exoPlayer.play()
        lastLoadedMediaKey = mediaKey
    }

    DisposableEffect(exoPlayer, currentVideo?.id) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playbackGateOpenRef.get()) {
                    playbackRequested = playWhenReady
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (playbackGateOpenRef.get() && isPlaying) {
                    playbackRequested = true
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && exoPlayer.duration > 0L) {
                    mediaDurationMs = exoPlayer.duration
                }
                if (playbackState == Player.STATE_ENDED) {
                    playbackRequested = false
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (latestVideo.value == null) return
                val httpStatus = generateSequence<Throwable>(error) { it.cause }.take(16)
                    .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>()
                    .firstOrNull()?.responseCode
                when (
                    val decision = decideVideoPlaybackRecovery(
                        errorCode = error.errorCode,
                        httpStatusCode = httpStatus,
                        previousAttempts = automaticPlaybackAttempts,
                    )
                ) {
                    is VideoPlaybackRecoveryDecision.Retry -> {
                        automaticPlaybackAttempts = decision.attempt
                        recoverPlayback(decision.delayMs, decision.refreshEndpoint, decision.evictCache)
                    }

                    VideoPlaybackRecoveryDecision.ShowError -> playbackError = true
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            lifecycleResumed = lifecycleOwner.lifecycle.currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            if (!lifecycleResumed) {
                playbackGateOpenRef.set(false)
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.pause()
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    LaunchedEffect(playbackGateOpen, playbackRequested, currentVideo?.id) {
        if (playbackGateOpen && playbackRequested) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    LaunchedEffect(currentVideo?.id, exoPlayer) {
        if (currentVideo == null) return@LaunchedEffect
        var lastHistorySampleAtMs = 0L
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                viewModel.onVideoPlaybackSample(
                    isActuallyPlaying = exoPlayer.isPlaying,
                    elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                )
                val sampleAtMs = android.os.SystemClock.elapsedRealtime()
                if (latestLinkedClip.value != null &&
                    exoPlayer.currentPosition >= latestLinkedClip.value!!.endMs) {
                    playbackRequested = false
                    exoPlayer.pause()
                }
                if (exoPlayer.isPlaying && sampleAtMs - lastHistorySampleAtMs >= 5_000L) {
                    historyViewModel.recordProgress(
                        currentVideo.id, exoPlayer.currentPosition, exoPlayer.duration)
                    lastHistorySampleAtMs = sampleAtMs
                }
                delay(250L)
            }
        } finally {
            historyViewModel.recordProgress(
                currentVideo.id, exoPlayer.currentPosition, exoPlayer.duration)
            viewModel.onVideoPlaybackSample(
                isActuallyPlaying = false,
                elapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
            )
        }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startRecording()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
                        )
                    }
                },
                actions = {
                    // Cast uiState to check if it's Success and user is owner
                    val currentState = uiState
                    if (currentState is VideoPlayerUiState.Success) {
                        if (viewModel.isOwner(currentState.video.userId)) {
                        var expanded by remember { mutableStateOf(false) }
                        var showDeleteDialog by remember { mutableStateOf(false) }
                        var showEditDialog by remember { mutableStateOf(false) }

                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Opciones", tint = Color.White)
                        }

                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Editar video") },
                                onClick = {
                                    expanded = false
                                    showEditDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Eliminar video", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    expanded = false
                                    showDeleteDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                }
                            )
                        }

                        if (showDeleteDialog) {
                            AlertDialog(
                                onDismissRequest = { showDeleteDialog = false },
                                title = { Text("¿Eliminar video?") },
                                text = { Text("Esta acción no se puede deshacer. El video desaparecerá de tu perfil y del feed.") },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            showDeleteDialog = false
                                            viewModel.deleteVideo {
                                                navController.popBackStack()
                                            }
                                        }
                                    ) {
                                        Text("Eliminar", color = MaterialTheme.colorScheme.error)
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showDeleteDialog = false }) {
                                        Text("Cancelar")
                                    }
                                }
                            )
                        }
                        
                        if (showEditDialog) {
                             EditVideoDialog(
                                initialTitle = currentState.video.title,
                                initialDescription = currentState.video.description,
                                initialPublic = currentState.video.isPublic,
                                initialAllowClipSharing = currentState.video.allowClipSharing,
                                onDismiss = { showEditDialog = false },
                                onConfirm = { title, desc, isPublic, allowClipSharing ->
                                    viewModel.updateVideo(title, desc, isPublic, allowClipSharing) {
                                        showEditDialog = false
                                    }
                                }
                             )
                         }
                    } else {
                        val alreadyReported = reportedVideoIds.contains(currentState.video.id)
                        IconButton(
                            onClick = {
                                viewModel.ensureVideoReportStatus(currentState.video.id)
                                showReportDialog = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Flag,
                                contentDescription = if (alreadyReported) "Reportado" else "Reportar video",
                                tint = if (alreadyReported) Color(0xFFFF9800) else Color.White
                            )
                        }
                    }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
            
            // Internal Share Dialog (placed outside TopAppBar for stability)
            val currentState = uiState
            if (showInternalShareDialog && currentState is VideoPlayerUiState.Success) {
                conectate.com.presentation.components.InternalShareDialog(
                    userRepository = viewModel.userRepo,
                    onDismiss = { showInternalShareDialog = false },
                    onShareWithUser = { user ->
                        showInternalShareDialog = false
                        val videoLink = "https://conectate.com/video/${currentState.video.id}"
                        val route = Screen.Chat.createRoute(
                            user.id,
                            initialMessage = "¡Mira este video!\n$videoLink\n\n${currentState.video.title}"
                        )
                        // Root fix: Deactivate player more aggressively to free main thread
                        exoPlayer.playWhenReady = false
                        exoPlayer.pause()
                        
                        // Root fix: Clear state and navigate immediately
                        showInternalShareDialog = false
                        navController.navigate(route = route)
                    }
                )
            }

            if (showReportDialog && currentState is VideoPlayerUiState.Success) {
                VideoReportDialog(
                    alreadyReported = reportedVideoIds.contains(currentState.video.id),
                    onDismiss = { showReportDialog = false },
                    onSubmit = { reason, details ->
                        viewModel.submitVideoReport(currentState.video.id, reason, details)
                        showReportDialog = false
                    }
                )
            }
        },
        containerColor = Color.Black
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize()) {
        if (!isConnected) {
            OfflineNoticeBar(
                message = "Sin conexión. Algunos cambios pueden esperar sincronización.",
                onRetry = {
                    connectivityViewModel.retrySync("video_player_retry")
                }
            )
        }
        when (val state = uiState) {
            is VideoPlayerUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White)
                }
            }

            is VideoPlayerUiState.Success -> {
                LaunchedEffect(focusCommentId, state.comments) {
                    if (!focusResolved && !focusCommentId.isNullOrBlank() &&
                        state.comments.none { it.id.equals(focusCommentId, ignoreCase = true) }
                    ) {
                        focusResolved = true
                        AppFeedbackBus.show("Ese comentario fue eliminado o ya no está disponible.")
                    }
                }
                val visibleThreads = remember(state.comments, commentOrder, commentAuthorFilter, viewModel.currentUserId, state.video.userId) {
                    visibleVideoCommentThreads(
                        state.comments, commentOrder, commentAuthorFilter,
                        viewModel.currentUserId, state.video.userId,
                    )
                }
                val focusParentId = remember(focusCommentId, state.comments) {
                    if (focusCommentId.isNullOrBlank()) {
                        null
                    } else {
                        visibleVideoCommentThreads(
                            state.comments, VideoCommentOrder.RECENT, VideoCommentAuthorFilter.ALL,
                            viewModel.currentUserId, state.video.userId,
                        ).firstOrNull { thread ->
                            thread.root.id.equals(focusCommentId, ignoreCase = true) ||
                                thread.replies.any { it.id.equals(focusCommentId, ignoreCase = true) }
                        }?.root?.id
                    }
                }

                LaunchedEffect(focusCommentId, visibleThreads, focusParentId) {
                    if (focusResolved || focusCommentId.isNullOrBlank() || focusParentId.isNullOrBlank()) {
                        return@LaunchedEffect
                    }
                    val parentIndex = visibleThreads.indexOfFirst { it.root.id.equals(focusParentId, ignoreCase = true) }
                    if (parentIndex >= 0) {
                        // 0 player, 1 info, 2 header, 3+ comments
                        listState.scrollToItem(parentIndex + 3)
                        focusResolved = true
                    }
                }

                // Main content with single scrollable area
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
// Video player already handled above Scaffold

                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            state = listState
                        ) {
                            // 1. Video Player Item
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AndroidView(
                                        factory = { ctx ->
                                            PlayerView(ctx).apply {
                                                player = exoPlayer
                                                useController = true
                                                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    if (playbackError) {
                                        Button(onClick = {
                                            automaticPlaybackAttempts = 0
                                            playbackRequested = true
                                            recoverPlayback(0, refreshEndpoint = true)
                                        }) {
                                            Text("Reintentar reproducción")
                                        }
                                    }
                                }
                            }

                            // 2. Video Info Item
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = state.video.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        Text(
                                            text = "$viewCount vistas",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                        )
                                        Text(
                                            text = state.video.created_at,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Action buttons
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        IconButton(onClick = { viewModel.toggleLike() }) {
                                            Icon(
                                                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                contentDescription = "Like",
                                                tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                                            )
                                        }
                                        Text(
                                            text = "$likeCount",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.align(Alignment.CenterVertically)
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Comment,
                                            contentDescription = "Comments",
                                            tint = MaterialTheme.colorScheme.onBackground
                                        )
                                         Text(
                                            text = "$commentCount",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.align(Alignment.CenterVertically)
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        // Share Button
                                        var showShareSheet by remember { mutableStateOf(false) }
                                        Box {
                                            IconButton(onClick = { showShareSheet = true }) {
                                                Icon(
                                                    imageVector = if (state.video.sharedByMe) Icons.Default.RepeatOn else Icons.Default.Share,
                                                    contentDescription = "Compartir",
                                                    tint = if (state.video.sharedByMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                                                )
                                            }
                                            
                                            if (showShareSheet) {
                                                conectate.com.presentation.components.ShareOptionsSheet(
                                                    onDismiss = { showShareSheet = false },
                                                    onSendToFriend = {
                                                        showInternalShareDialog = true
                                                    },
                                                    onExternalShare = {
                                                        val sendIntent = android.content.Intent().apply {
                                                            action = android.content.Intent.ACTION_SEND
                                                            putExtra(android.content.Intent.EXTRA_TEXT, "¡Mira este video en Novvin!\nhttps://conectate.com/video/${state.video.id}\n\nO abre directamente en la app:\nconectate://video/${state.video.id}\n\n${state.video.title}")
                                                            type = "text/plain"
                                                        }
                                                        val shareIntent = android.content.Intent.createChooser(sendIntent, "Compartir video")
                                                        context.startActivity(shareIntent)
                                                    },
                                                    onToggleProfileShare = {
                                                        viewModel.toggleShare()
                                                    },
                                                    isSharedToProfile = state.video.sharedByMe
                                                )
                                            }
                                        }
                                        Text(
                                            text = "$shareCount",
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.align(Alignment.CenterVertically),
                                            color = if (state.video.sharedByMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                                        )
                                    }

                                    if (state.video.description.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = state.video.description,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.height(16.dp))
                                    HorizontalDivider()
                                }
                            }

                            if (linkedClip != null) {
                                item {
                                    Text("Fragmento compartido: ${videoChapterTimeLabel(linkedClip.startMs)}–${videoChapterTimeLabel(linkedClip.endMs)}",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                    TextButton(onClick = {
                                        exoPlayer.seekTo(linkedClip.startMs)
                                        playbackRequested = true
                                    }) { Text("Reproducir fragmento de nuevo") }
                                }
                            }

                            if (state.video.isPublic && state.video.allowClipSharing) {
                                item {
                                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            val durationMs = mediaDurationMs
                                            val startMs = exoPlayer.currentPosition.coerceIn(
                                                0L, (durationMs - 5_000L).coerceAtLeast(0L))
                                            clipStartText = videoChapterTimeLabel(startMs)
                                            clipEndText = videoChapterTimeLabel(
                                                (startMs + 10_000L).coerceAtMost(durationMs))
                                            showClipComposer = !showClipComposer
                                        }, enabled = mediaDurationMs >= 5_000L,
                                            modifier = Modifier.fillMaxWidth()) {
                                            Text("Compartir fragmento")
                                        }
                                        if (showClipComposer) {
                                            Text("El enlace abre el video original. Elige entre 5 y 60 segundos.",
                                                style = MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedTextField(value = clipStartText,
                                                    onValueChange = { clipStartText = it.take(8) },
                                                    label = { Text("Inicio m:ss") },
                                                    singleLine = true, modifier = Modifier.weight(1f))
                                                OutlinedTextField(value = clipEndText,
                                                    onValueChange = { clipEndText = it.take(8) },
                                                    label = { Text("Fin m:ss") },
                                                    singleLine = true, modifier = Modifier.weight(1f))
                                            }
                                            val range = validVideoClipRange(
                                                parseVideoChapterTime(clipStartText) ?: -1L,
                                                parseVideoChapterTime(clipEndText) ?: -1L,
                                                mediaDurationMs)
                                            Button(onClick = {
                                                val selected = range ?: return@Button
                                                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(android.content.Intent.EXTRA_TEXT,
                                                        "${state.video.title}\n${videoClipLink(state.video.id, selected)}" +
                                                            "\nconectate://video/${state.video.id}?resumeMs=${selected.startMs}&endMs=${selected.endMs}")
                                                }
                                                context.startActivity(android.content.Intent.createChooser(intent,
                                                    "Compartir fragmento"))
                                            }, enabled = range != null, modifier = Modifier.fillMaxWidth()) {
                                                Text("Compartir enlace")
                                            }
                                        }
                                    }
                                }
                            }

                            if (state.video.chapters.isNotEmpty()) {
                                item {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text("Capítulos", style = MaterialTheme.typography.titleMedium)
                                        state.video.chapters.forEach { chapter ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                TextButton(
                                                    onClick = {
                                                        exoPlayer.seekTo(chapter.startMs)
                                                        playbackRequested = true
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    Text("${videoChapterTimeLabel(chapter.startMs)}  ${chapter.title}",
                                                        maxLines = 2)
                                                }
                                                IconButton(onClick = {
                                                    val link = "https://conectate.com/video/${state.video.id}?resumeMs=${chapter.startMs}"
                                                    val appLink = "conectate://video/${state.video.id}?resumeMs=${chapter.startMs}"
                                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                        type = "text/plain"
                                                        putExtra(android.content.Intent.EXTRA_TEXT,
                                                            "${state.video.title} · ${chapter.title}\n$link\n$appLink")
                                                    }
                                                    context.startActivity(android.content.Intent.createChooser(intent,
                                                        "Compartir capítulo"))
                                                }) {
                                                    Icon(Icons.Default.Share, contentDescription = "Compartir capítulo ${chapter.title}")
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 3. Comments Header
                            item {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "Comentarios",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    VideoCommentFilterControls(
                                        order = commentOrder,
                                        authorFilter = commentAuthorFilter,
                                        onOrderChange = { commentOrder = it },
                                        onAuthorFilterChange = { commentAuthorFilter = it },
                                    )
                                    if (state.comments.isNotEmpty() && visibleThreads.isEmpty()) {
                                        Text("No hay comentarios con este filtro")
                                    }
                                }
                            }

                            // 4. Comments List
                            itemsIndexed(visibleThreads, key = { _, item -> item.root.id }) { _, thread ->
                                CommentThreadItem(
                                    comment = thread.root,
                                    replies = thread.replies,
                                    onPlayAudio = { viewModel.playAudio(it) },
                                    isPlaying = currentPlayingCommentId == thread.root.id,
                                    progress = if (currentPlayingCommentId == thread.root.id) audioProgress else 0f,
                                    currentPlayingCommentId = currentPlayingCommentId,
                                    audioProgress = audioProgress,
                                    currentUserId = viewModel.currentUserId,
                                    onDeleteComment = { viewModel.deleteComment(it) },
                                    onProfileClick = { userId ->
                                        navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(userId))
                                    },
                                    videoCreatorId = state.video.userId,
                                    onReplyClick = { target -> viewModel.onReplyClick(target) },
                                    onReactComment = { target, reaction -> viewModel.reactToComment(target, reaction) },
                                    onEditComment = { target, content -> viewModel.editComment(target, content) },
                                    onReportComment = { target -> viewModel.reportComment(target) },
                                    initiallyExpanded = focusParentId != null &&
                                        focusParentId.equals(thread.root.id, ignoreCase = true) &&
                                        !focusCommentId.equals(thread.root.id, ignoreCase = true)
                                )
                                HorizontalDivider()
                            }
                        }

                        // FIXED: Comment input at the bottom
                        Surface(tonalElevation = 3.dp) {
                            if (voiceState != conectate.com.presentation.messages.VoiceState.IDLE) {
                                // --- RECORDING / REVIEW UI ---
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                        .height(64.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                        .padding(horizontal = 8.dp)
                                ) {
                                    // Cancel / Delete
                                    IconButton(onClick = { viewModel.cancelRecording() }) {
                                        Icon(Icons.Default.Delete, "Cancelar", tint = MaterialTheme.colorScheme.error)
                                    }
                                    
                                    Spacer(modifier = Modifier.width(8.dp))
                                    
                                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                        when (voiceState) {
                                            conectate.com.presentation.messages.VoiceState.RECORDING -> {
                                                val minutes = recordingDuration / 1000 / 60
                                                val seconds = (recordingDuration / 1000) % 60
                                                val timeString = String.format(
                                                    androidx.compose.ui.platform.LocalLocale.current.platformLocale,
                                                    "%02d:%02d",
                                                    minutes,
                                                    seconds,
                                                )
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        "Grabando... $timeString", 
                                                        color = MaterialTheme.colorScheme.error,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                            conectate.com.presentation.messages.VoiceState.PAUSED -> {
                                                Text(
                                                    "Pausado", 
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            conectate.com.presentation.messages.VoiceState.REVIEWING -> {
                                                // Review Player
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                                    IconButton(onClick = { 
                                                        if (isDraftPlaying) viewModel.pauseDraftPreview() 
                                                        else viewModel.playDraftPreview()
                                                    }) {
                                                        Icon(
                                                            if (isDraftPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, 
                                                            "Reproducir",
                                                            tint = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                    
                                                    Slider(
                                                        value = voicePlaybackProgress,
                                                        onValueChange = { viewModel.seekDraftPreview(it) },
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    
                                                    Text(
                                                        voicePlaybackDurationLabel,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        modifier = Modifier.padding(start = 8.dp)
                                                    )
                                                }
                                            }
                                            else -> {}
                                        }
                                    }
                                    
                                    // Actions
                                    when (voiceState) {
                                        conectate.com.presentation.messages.VoiceState.RECORDING -> {
                                            IconButton(onClick = { viewModel.pauseRecording() }) {
                                                Icon(Icons.Default.Pause, "Pausar", tint = MaterialTheme.colorScheme.primary)
                                            }
                                            IconButton(
                                                onClick = { viewModel.stopRecording(true) }
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                        conectate.com.presentation.messages.VoiceState.PAUSED -> {
                                            IconButton(onClick = { viewModel.resumeRecording() }) {
                                                Icon(Icons.Default.Mic, "Reanudar", tint = MaterialTheme.colorScheme.primary)
                                            }
                                            IconButton(onClick = { viewModel.stopRecording(false) }) {
                                                Icon(Icons.Default.Stop, "Revisar", tint = MaterialTheme.colorScheme.secondary)
                                            }
                                        }
                                        conectate.com.presentation.messages.VoiceState.REVIEWING -> {
                                            IconButton(
                                                onClick = { viewModel.resumeRecordingFromReview() }
                                            ) {
                                                Icon(Icons.Default.Mic, "Reanudar", tint = MaterialTheme.colorScheme.primary)
                                            }
                                            IconButton(
                                                onClick = { viewModel.sendCurrentRecording() }
                                            ) {
                                                Icon(Icons.AutoMirrored.Filled.Send, "Enviar", tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                        else -> {}
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    androidx.compose.animation.AnimatedVisibility(visible = replyingTo != null) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Respondiendo a @${replyingTo?.userName ?: "Usuario"}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            IconButton(onClick = { viewModel.cancelReply() }, modifier = Modifier.size(20.dp)) {
                                                Icon(Icons.Default.Close, contentDescription = "Cancelar respuesta")
                                            }
                                        }
                                    }

                                    if (mentionSuggestions.isNotEmpty()) {
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp),
                                            shape = RoundedCornerShape(12.dp),
                                            tonalElevation = 2.dp
                                        ) {
                                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                                mentionSuggestions.forEach { user ->
                                                    val username = user.username ?: return@forEach
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                viewModel.updateCommentText(
                                                                    replaceActiveMention(commentText, username)
                                                                )
                                                                viewModel.clearMentionSuggestions()
                                                            }
                                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        CircularProfileImage(
                                                            imageUrl = user.avatar_url ?: "",
                                                            size = 26.dp,
                                                            fallbackName = user.displayName
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text("@$username", fontWeight = FontWeight.SemiBold)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.Bottom
                                    ) {
                                        IconButton(onClick = { showStickerPanel = true }) {
                                            Icon(Icons.Default.EmojiEmotions, contentDescription = "Sticker")
                                        }
                                        OutlinedTextField(
                                            value = commentText,
                                            onValueChange = { viewModel.updateCommentText(it) },
                                            modifier = Modifier.weight(1f),
                                            placeholder = {
                                                Text(if (replyingTo != null) "Escribe una respuesta..." else "Escribe un comentario...")
                                            },
                                            maxLines = 3,
                                            shape = CircleShape
                                        )

                                        if (commentText.isEmpty()) {
                                            // Mic Button
                                            IconButton(
                                                onClick = {
                                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                                        viewModel.startRecording()
                                                    } else {
                                                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                                    }
                                                },
                                                colors = IconButtonDefaults.iconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.primary,
                                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                                )
                                            ) {
                                                Icon(Icons.Default.Mic, contentDescription = "Grabar")
                                            }
                                        } else {
                                            IconButton(
                                                onClick = { viewModel.postComment() },
                                                enabled = commentText.isNotBlank(),
                                                colors = IconButtonDefaults.iconButtonColors(
                                                    containerColor = MaterialTheme.colorScheme.primary,
                                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                                    contentDescription = "Enviar"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            is VideoPlayerUiState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.HideSource,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { navController.popBackStack() }) {
                        Text("Volver")
                    }
                }
            }
        }
        }
    }

    conectate.com.presentation.messages.stickers.StickerPanel(
        visible = showStickerPanel,
        onDismiss = { showStickerPanel = false },
        onStickerSelected = { sticker ->
            showStickerPanel = false
            val url = sticker.media_url.orEmpty()
            if (url.isNotBlank()) viewModel.addStickerComment(url, sticker.title.ifBlank { "Sticker" })
        }
    )
}

// Local CommentItem removed, using VideoCommentItem from CommentSheet.kt
@Composable
fun EditVideoDialog(
    initialTitle: String,
    initialDescription: String,
    initialPublic: Boolean,
    initialAllowClipSharing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean, Boolean) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf(initialDescription) }
    var isPublic by remember { mutableStateOf(initialPublic) }
    var allowClipSharing by remember { mutableStateOf(initialAllowClipSharing) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar Video") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Título") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descripción") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isPublic,
                        onCheckedChange = { isPublic = it }
                    )
                    Text("Público")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowClipSharing,
                        onCheckedChange = { allowClipSharing = it })
                    Text("Permitir enlaces a fragmentos")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, description, isPublic, allowClipSharing) },
                enabled = title.isNotBlank()
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
private fun formatDate(date: Date?): String {
    if (date == null) return ""
    val now = Date()
    val diff = now.time - date.time

    return when {
        diff < 60000 -> "hace ${(diff / 1000).coerceAtLeast(0)} s"
        diff < 3600000 -> "hace ${diff / 60000} min"
        diff < 86400000 -> "hace ${diff / 3600000} h"
        diff < 604800000 -> SimpleDateFormat("EEE", Locale.getDefault()).format(date)
        else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(date)
    }
}

private fun replaceActiveMention(currentText: String, username: String): String {
    val atIndex = currentText.lastIndexOf('@')
    if (atIndex < 0) return "$currentText @$username "

    val suffix = currentText.substring(atIndex + 1)
    if (suffix.any { it.isWhitespace() }) return "$currentText@$username "

    return currentText.substring(0, atIndex + 1) + username + " "
}

private fun String?.isRootVideoCommentParent(): Boolean {
    val clean = this?.trim()?.lowercase()
    return clean.isNullOrBlank() || clean == "0" || clean == "null" || clean == "undefined" || clean == "none"
}
