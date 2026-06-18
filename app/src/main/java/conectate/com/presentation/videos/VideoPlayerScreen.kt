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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import conectate.com.presentation.navigation.Screen
import conectate.com.data.repository.AuthRepository
import conectate.com.data.model.Video
import conectate.com.data.model.VideoComment
import conectate.com.data.repository.VideoRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.ShareRepository
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.common.ConnectivityViewModel
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.OfflineNoticeBar
import conectate.com.util.AudioRecorder
import conectate.com.util.UbuntuMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.media.MediaPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

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
    private val shareRepository: ShareRepository
) : ViewModel() {

    init {
        voiceMessageManager.setScope(viewModelScope)
    }

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
                // Increment view count
                videoRepository.incrementViews(videoId)

                // Load video and comments
                val videoResult = videoRepository.getVideoById(videoId)
                videoResult.fold(
                    onSuccess = { video ->
                        _isLiked.value = video.likedByMe
                        // Counts are handled by observeStats() to ensure single source of truth (Room + Realtime)
                        // _likeCount.value = video.likeCount
                        // _commentCount.value = video.commentCount
                        // _viewCount.value = video.views

                        commentsSubscriptionJob?.cancel()
                        refreshComments(video, preserveCurrent = false)
                    },
                    onFailure = { error ->
                        _uiState.value = VideoPlayerUiState.Error(
                            error.message ?: "Error al cargar video"
                        )
                    }
                )
            } catch (e: Exception) {
                _uiState.value = VideoPlayerUiState.Error(e.message ?: "Error")
            }
        }
    }

    private fun observeStats() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            videoRepository.getVideoStatsFlow(videoId).collect { stats ->
                if (stats != null) {
                    _likeCount.value = stats.likeCount
                    _commentCount.value = stats.commentCount
                    _viewCount.value = maxOf(_viewCount.value, stats.viewCount)
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
                    _commentCount.value = maxOf(_commentCount.value, nextComments.size)
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

    private fun refreshCommentsAfterWrite() {
        viewModelScope.launch {
            delay(250)
            val currentVideo = (_uiState.value as? VideoPlayerUiState.Success)?.video
            refreshComments(currentVideo, preserveCurrent = true)
        }
    }

    private suspend fun refreshComments(video: Video?, preserveCurrent: Boolean) {
        val loaded = videoRepository.loadVideoCommentsNow(videoId)
            .onFailure { error ->
                android.util.Log.e("VideoPlayerVM", "Failed to refresh comments", error)
            }
            .getOrNull()
            ?: emptyList()
        val currentState = _uiState.value
        val currentComments = if (preserveCurrent && currentState is VideoPlayerUiState.Success) {
            currentState.comments
        } else {
            emptyList()
        }
        val nextComments = mergeComments(currentComments, loaded)
        if (currentState is VideoPlayerUiState.Success) {
            _uiState.value = currentState.copy(comments = nextComments)
        } else if (video != null) {
            _uiState.value = VideoPlayerUiState.Success(
                video = video,
                comments = nextComments,
                isLiked = _isLiked.value
            )
        }
        _commentCount.value = maxOf(_commentCount.value, nextComments.size)
    }

    private fun mergeComments(
        current: List<VideoComment>,
        incoming: List<VideoComment>
    ): List<VideoComment> {
        return (current + incoming)
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id.trim().lowercase() }
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
        viewModelScope.launch {
            videoRepository.deleteComment(comment.id).onSuccess {
                val currentState = _uiState.value
                if (currentState is VideoPlayerUiState.Success) {
                    val nextComments = currentState.comments.filterNot { it.id.equals(comment.id, ignoreCase = true) }
                    _uiState.value = currentState.copy(comments = nextComments)
                    _commentCount.value = nextComments.size
                }
                refreshCommentsAfterWrite()
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

    fun updateVideo(title: String, description: String, isPublic: Boolean, onSuccess: () -> Unit) {
        viewModelScope.launch {
            videoRepository.updateVideo(videoId, title, description, isPublic).onSuccess {
                 onSuccess()
                 loadVideo() // Reload to reflect changes
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
                                    likeCount = maxOf(latestState.video.likeCount, refreshed.likeCount),
                                    commentCount = maxOf(latestState.video.commentCount, refreshed.commentCount),
                                    shareCount = maxOf(newShareCount, refreshed.shareCount),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    navController: NavController,
    focusCommentId: String? = null,
    viewModel: VideoPlayerViewModel = hiltViewModel(),
    connectivityViewModel: ConnectivityViewModel = hiltViewModel()
) {
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
    
    var showInternalShareDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var focusResolved by remember(focusCommentId) { mutableStateOf(focusCommentId.isNullOrBlank()) }

    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(reportActionMessage) {
        val message = reportActionMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        viewModel.consumeReportActionMessage()
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build()
    }

    LaunchedEffect(uiState) {
        if (uiState is VideoPlayerUiState.Success) {
            val state = uiState as VideoPlayerUiState.Success
            if (exoPlayer.mediaItemCount == 0) {
                exoPlayer.setMediaItem(UbuntuMedia.mediaItem(state.video.videoUrl))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    // Pause playback when internal share dialog is shown
    LaunchedEffect(showInternalShareDialog) {
        if (showInternalShareDialog) {
            exoPlayer.pause()
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
                            imageVector = Icons.Filled.ArrowBack,
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
                                text = { Text("Editar publicación") },
                                onClick = {
                                    expanded = false
                                    showEditDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Eliminar publicación", color = MaterialTheme.colorScheme.error) },
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
                                onDismiss = { showEditDialog = false },
                                onConfirm = { title, desc, isPublic ->
                                    showEditDialog = false
                                    viewModel.updateVideo(title, desc, isPublic) {
                                        // Success
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
                val topLevelComments = remember(state.comments) {
                    state.comments.filter { it.parentId.isRootVideoCommentParent() }
                }
                val repliesByParent = remember(state.comments) {
                    state.comments
                        .filter { !it.parentId.isRootVideoCommentParent() }
                        .groupBy { it.parentId }
                }
                val focusParentId = remember(focusCommentId, state.comments) {
                    if (focusCommentId.isNullOrBlank()) {
                        null
                    } else {
                        val direct = state.comments.firstOrNull { it.id.equals(focusCommentId, ignoreCase = true) }
                        direct?.parentId ?: direct?.id
                    }
                }

                LaunchedEffect(focusCommentId, topLevelComments, focusParentId) {
                    if (focusResolved || focusCommentId.isNullOrBlank() || focusParentId.isNullOrBlank()) {
                        return@LaunchedEffect
                    }
                    val parentIndex = topLevelComments.indexOfFirst { it.id.equals(focusParentId, ignoreCase = true) }
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
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            player = exoPlayer
                                            useController = true
                                            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(16f / 9f)
                                )
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
                                            imageVector = Icons.Default.Comment,
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
                                                            putExtra(android.content.Intent.EXTRA_TEXT, "¡Mira este video en Conéctate!\nhttps://conectate.com/video/${state.video.id}\n\nO abre directamente en la app:\nconectate://video/${state.video.id}\n\n${state.video.title}")
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
                                    Divider()
                                }
                            }

                            // 3. Comments Header
                            item {
                                Text(
                                    text = "Comentarios",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }

                            // 4. Comments List
                            itemsIndexed(topLevelComments, key = { _, item -> item.id }) { _, comment ->
                                CommentThreadItem(
                                    comment = comment,
                                    replies = repliesByParent[comment.id] ?: emptyList(),
                                    onPlayAudio = { viewModel.playAudio(it) },
                                    isPlaying = currentPlayingCommentId == comment.id,
                                    progress = if (currentPlayingCommentId == comment.id) audioProgress else 0f,
                                    currentPlayingCommentId = currentPlayingCommentId,
                                    audioProgress = audioProgress,
                                    currentUserId = viewModel.currentUserId,
                                    onDeleteComment = { viewModel.deleteComment(it) },
                                    onProfileClick = { userId ->
                                        navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(userId))
                                    },
                                    videoCreatorId = state.video.userId,
                                    onReplyClick = { target -> viewModel.onReplyClick(target) },
                                    initiallyExpanded = focusParentId != null &&
                                        focusParentId.equals(comment.id, ignoreCase = true) &&
                                        !focusCommentId.equals(comment.id, ignoreCase = true)
                                )
                                Divider()
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
                                                val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
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
                                                Icon(Icons.Default.Send, "Enviar", tint = MaterialTheme.colorScheme.primary)
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
                                                Icon(Icons.Default.Send, "Enviar", tint = MaterialTheme.colorScheme.primary)
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
                                                            size = 26.dp
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
                                                    imageVector = Icons.Filled.Send,
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        }
    }
}

// Local CommentItem removed, using VideoCommentItem from CommentSheet.kt
@Composable
fun EditVideoDialog(
    initialTitle: String,
    initialDescription: String,
    initialPublic: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var description by remember { mutableStateOf(initialDescription) }
    var isPublic by remember { mutableStateOf(initialPublic) }

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
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, description, isPublic) },
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
