package conectate.com.presentation.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Video
import conectate.com.data.repository.VideoRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.media.MediaPlayer
import conectate.com.util.AudioRecorder
import java.io.File
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VideoFeedViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val voiceMessageManager: conectate.com.presentation.messages.VoiceMessageManager, // Injected
    private val authService: AuthService,
    val userRepository: conectate.com.data.repository.UserRepository,
    private val shareRepository: conectate.com.data.repository.ShareRepository
) : ViewModel() {
    
    // Two separate lists to avoid reloading when switching tabs
    private val _forYouVideos = MutableStateFlow<List<Video>>(emptyList())
    val forYouVideos: StateFlow<List<Video>> = _forYouVideos.asStateFlow()

    private val _followingVideos = MutableStateFlow<List<Video>>(emptyList())
    val followingVideos: StateFlow<List<Video>> = _followingVideos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _selectedTab = MutableStateFlow(0) // 0 = For You, 1 = Following
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    // Search State
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Video>>(emptyList())
    val searchResults: StateFlow<List<Video>> = _searchResults.asStateFlow()

    private val _isSearchMode = MutableStateFlow(false)
    val isSearchMode: StateFlow<Boolean> = _isSearchMode.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null
    private var mentionSearchJob: Job? = null

    private var currentPage = 0
    private val pageSize = 20
    private var isLastPage = false
    private val likedDebounceJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val shareRequestsInFlight = mutableSetOf<String>()

    // Comments State
    private val _commentsSheetOpen = MutableStateFlow(false)
    val commentsSheetOpen: StateFlow<Boolean> = _commentsSheetOpen.asStateFlow()
    
    private val _selectedVideoForComments = MutableStateFlow<Video?>(null)
    val selectedVideoForComments: StateFlow<Video?> = _selectedVideoForComments.asStateFlow()
    
    private val _comments = MutableStateFlow<List<conectate.com.data.model.VideoComment>>(emptyList())
    val comments: StateFlow<List<conectate.com.data.model.VideoComment>> = _comments.asStateFlow()

    private val _mentionSuggestions = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val mentionSuggestions: StateFlow<List<conectate.com.data.model.User>> = _mentionSuggestions.asStateFlow()
    private val _reportedVideoIds = MutableStateFlow<Set<String>>(emptySet())
    val reportedVideoIds: StateFlow<Set<String>> = _reportedVideoIds.asStateFlow()
    private val _reportActionMessage = MutableStateFlow<String?>(null)
    val reportActionMessage: StateFlow<String?> = _reportActionMessage.asStateFlow()
    private val reportRequestsInFlight = mutableSetOf<String>()

    // Audio Comment State (Delegated to VoiceMessageManager)
    val voiceState = voiceMessageManager.state
    val recordingDuration = voiceMessageManager.duration
    
    // Draft Preview State
    val isDraftPlaying = voiceMessageManager.isDraftPlaying
    val draftProgress = voiceMessageManager.playbackProgress
    val draftDuration = voiceMessageManager.playbackDurationLabel

    private val _currentPlayingCommentId = MutableStateFlow<String?>(null)
    val currentPlayingCommentId: StateFlow<String?> = _currentPlayingCommentId.asStateFlow()

    private val _audioProgress = MutableStateFlow(0f)
    val audioProgress: StateFlow<Float> = _audioProgress.asStateFlow()

    val currentUserId: String? = authService.getCurrentUserId()

    // Follow Status Tracking
    private val _followedUsers = MutableStateFlow<Set<String>>(emptySet())
    val followedUsers: StateFlow<Set<String>> = _followedUsers.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var commentsJob: Job? = null

    init {
        voiceMessageManager.setScope(viewModelScope)
        loadForYouVideos()
        loadFollowedUsers()
    }

    private fun loadFollowedUsers() {
        viewModelScope.launch {
            try {
                // Get unique user IDs from current videos
                val allVideos = _forYouVideos.value + _followingVideos.value
                val userIds = allVideos.map { it.userId }.distinct()
                
                // Check follow status for each user
                val followed = mutableSetOf<String>()
                userIds.forEach { userId ->
                    val result = userRepository.checkIsFollowing(userId)
                    if (result.getOrDefault(false)) {
                        followed.add(userId)
                    }
                }
                _followedUsers.value = followed
            } catch (e: Exception) {
                android.util.Log.e("VideoFeedVM", "Error loading followed users", e)
            }
        }
    }
    
    // Public function to refresh follow status (e.g., after unfollowing from profile)
    fun refreshFollowStatus() {
        loadFollowedUsers()
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }

        searchJob = viewModelScope.launch {
            delay(500) // Debounce search
            try {
                _isSearching.value = true
                videoRepository.searchVideos(query).onSuccess { videos ->
                    _searchResults.value = videos
                    _isSearching.value = false
                }.onFailure {
                    _isSearching.value = false
                }
            } catch (e: Exception) {
                _isSearching.value = false
            }
        }
    }

    fun toggleSearchMode(enabled: Boolean) {
        _isSearchMode.value = enabled
        if (!enabled) {
            _searchQuery.value = ""
            _searchResults.value = emptyList()
        }
    }

    fun onTabSelected(index: Int) {
        _selectedTab.value = index
        if (index == 0 && _forYouVideos.value.isEmpty()) {
            loadForYouVideos()
        } else if (index == 1 && _followingVideos.value.isEmpty()) {
            loadFollowingVideos()
        }
    }

    private fun loadForYouVideos() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                currentPage = 0
                isLastPage = false
                videoRepository.getVideos(offset = 0, limit = pageSize, random = true).collect { newVideos ->
                    _forYouVideos.value = newVideos
                    isLastPage = newVideos.size < pageSize
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoFeedVM", "Error loading for you videos", e)
                _isLoading.value = false
            }
        }
    }

    fun loadMoreVideos() {
        if (_isLoading.value || isLastPage) return
        
        viewModelScope.launch {
            try {
                currentPage++
                val offset = currentPage * pageSize
                videoRepository.getVideos(offset = offset, limit = pageSize, random = true).collect { newVideos ->
                    if (newVideos.isEmpty()) {
                        isLastPage = true
                    } else {
                        _forYouVideos.value += newVideos
                        if (newVideos.size < pageSize) isLastPage = true
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoFeedVM", "Error loading more videos", e)
            }
        }
    }

    private fun loadFollowingVideos() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                videoRepository.getFollowingVideos().collect {
                    _followingVideos.value = it
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoFeedVM", "Error loading following videos", e)
                _isLoading.value = false
            }
        }
    }

    fun incrementVideoViews(videoId: String) {
        viewModelScope.launch {
            videoRepository.incrementViews(videoId)
        }
    }

    fun toggleLike(video: Video) {
        val videoId = video.id
        val snapshotForYou = _forYouVideos.value
        val snapshotFollowing = _followingVideos.value
        val snapshotSearch = _searchResults.value

        val current = findVideoById(videoId) ?: video
        val newLikedState = !current.likedByMe

        _forYouVideos.value = applyLikeState(_forYouVideos.value, videoId, newLikedState)
        _followingVideos.value = applyLikeState(_followingVideos.value, videoId, newLikedState)
        _searchResults.value = applyLikeState(_searchResults.value, videoId, newLikedState)

        likedDebounceJobs[videoId]?.cancel()
        val scheduledJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)

            val result = if (newLikedState) {
                videoRepository.likeVideo(videoId)
            } else {
                videoRepository.unlikeVideo(videoId)
            }

            if (result.isFailure) {
                android.util.Log.w(
                    "VideoFeedVM",
                    "Like sync failed for $videoId: ${result.exceptionOrNull()?.message}"
                )
                restoreVideoListsSnapshot(snapshotForYou, snapshotFollowing, snapshotSearch)
            }
        }

        likedDebounceJobs[videoId] = scheduledJob
        scheduledJob.invokeOnCompletion {
            if (likedDebounceJobs[videoId] === scheduledJob) {
                likedDebounceJobs.remove(videoId)
            }
        }
    }

    fun toggleShare(video: Video) {
        val currentId = currentUserId ?: return
        val videoId = video.id
        if (!shareRequestsInFlight.add(videoId)) return

        val snapshotForYou = _forYouVideos.value
        val snapshotFollowing = _followingVideos.value
        val snapshotSearch = _searchResults.value

        val current = findVideoById(videoId) ?: video
        val newSharedState = !current.sharedByMe
        val delta = if (newSharedState) 1 else -1

        _forYouVideos.value = applyShareState(_forYouVideos.value, videoId, newSharedState)
        _followingVideos.value = applyShareState(_followingVideos.value, videoId, newSharedState)
        _searchResults.value = applyShareState(_searchResults.value, videoId, newSharedState)

        viewModelScope.launch {
            try {
                val shareResult = if (newSharedState) {
                    shareRepository.shareContent(currentId, "video", videoId)
                } else {
                    shareRepository.unshareContent(currentId, "video", videoId)
                }

                if (shareResult.isFailure) {
                    restoreVideoListsSnapshot(snapshotForYou, snapshotFollowing, snapshotSearch)
                    return@launch
                }

                val countResult = videoRepository.incrementShareCount(videoId, delta)
                if (countResult.isFailure) {
                    restoreVideoListsSnapshot(snapshotForYou, snapshotFollowing, snapshotSearch)
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoFeedVM", "Share toggle failed", e)
                restoreVideoListsSnapshot(snapshotForYou, snapshotFollowing, snapshotSearch)
            } finally {
                shareRequestsInFlight.remove(videoId)
            }
        }
    }

    private fun restoreVideoListsSnapshot(
        forYou: List<Video>,
        following: List<Video>,
        search: List<Video>
    ) {
        _forYouVideos.value = forYou
        _followingVideos.value = following
        _searchResults.value = search
    }

    private fun findVideoById(videoId: String): Video? {
        return _forYouVideos.value.firstOrNull { it.id == videoId }
            ?: _followingVideos.value.firstOrNull { it.id == videoId }
            ?: _searchResults.value.firstOrNull { it.id == videoId }
    }

    private fun applyLikeState(list: List<Video>, videoId: String, liked: Boolean): List<Video> {
        return list.map { item ->
            if (item.id != videoId) item
            else {
                val newCount = if (liked) item.likeCount + 1 else maxOf(0, item.likeCount - 1)
                item.copy(likedByMe = liked, likeCount = newCount)
            }
        }
    }

    private fun applyShareState(list: List<Video>, videoId: String, shared: Boolean): List<Video> {
        return list.map { item ->
            if (item.id != videoId) item
            else {
                val newCount = if (shared) item.shareCount + 1 else maxOf(0, item.shareCount - 1)
                item.copy(sharedByMe = shared, shareCount = newCount)
            }
        }
    }
    
    fun openComments(video: Video) {
        _selectedVideoForComments.value = video
        _commentsSheetOpen.value = true
        fetchComments(video.id)
    }
    
    fun closeComments() {
        commentsJob?.cancel()
        _commentsSheetOpen.value = false
        _selectedVideoForComments.value = null
        _comments.value = emptyList()
        clearMentionSuggestions()
        stopAudio()
        voiceMessageManager.reset() // Reset recording/preview on close
    }
    
    private fun fetchComments(videoId: String) {
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            videoRepository.getCommentsFlow(videoId).collect { comments ->
                _comments.value = comments
                // CRITICAL: Manually update the video's comment count in the list to match reality
                // This fixes the issue where Server (31) != Real (5)
                val count = comments.size
                
                fun updateCount(list: List<Video>): List<Video> {
                    return list.map { 
                        if (it.id == videoId) it.copy(commentCount = count) else it 
                    }
                }
                _forYouVideos.value = updateCount(_forYouVideos.value)
                _followingVideos.value = updateCount(_followingVideos.value)
            }
        }
    }
    
    // Reply State
    private val _replyingTo = MutableStateFlow<conectate.com.data.model.VideoComment?>(null)
    val replyingTo: StateFlow<conectate.com.data.model.VideoComment?> = _replyingTo.asStateFlow()

    fun onReplyClick(comment: conectate.com.data.model.VideoComment) {
        _replyingTo.value = comment
    }

    fun cancelReply() {
        _replyingTo.value = null
    }

    fun addComment(videoId: String, content: String) {
         if (content.isBlank()) return
         val parentId = _replyingTo.value?.id
         
         viewModelScope.launch {
             android.util.Log.d("VideoFeedVM", "addComment called for videoId: $videoId, parentId: $parentId")
             videoRepository.addComment(videoId, content, parentId = parentId)
                 .onSuccess { 
                     android.util.Log.d("VideoFeedVM", "Comment added successfully")
                     cancelReply() 
                     clearMentionSuggestions()
                     fetchComments(videoId)
                     // Reload video to get accurate server-side count
                     reloadVideo(videoId)
                 }
                 .onFailure { e ->
                     android.util.Log.e("VideoFeedVM", "Error adding comment: ${e.message}", e)
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

    fun deleteComment(videoId: String, comment: conectate.com.data.model.VideoComment) {
        viewModelScope.launch {
            videoRepository.deleteComment(comment.id)
                .onSuccess {
                    android.util.Log.d("VideoFeedVM", "Comment deleted successfully")
                    fetchComments(videoId)
                    // Reload video to get accurate server-side count (handles cascade deletes)
                    reloadVideo(videoId)
                }
                .onFailure { e ->
                    android.util.Log.e("VideoFeedVM", "Error deleting comment", e)
                }
        }
    }

    private fun reloadVideo(videoId: String) {
        viewModelScope.launch {
            // Wait for DB triggers/functions to update counts
            delay(300) 
            videoRepository.getVideo(videoId).onSuccess { updatedVideo ->
                // Update lists with new video data (including counts)
                fun updateList(list: List<Video>): List<Video> {
                    return list.map { if (it.id == videoId) updatedVideo else it }
                }
                _forYouVideos.value = updateList(_forYouVideos.value)
                _followingVideos.value = updateList(_followingVideos.value)
            }.onFailure {
                android.util.Log.e("VideoFeedVM", "Failed to reload video after comment update")
            }
        }
    }

    // Audio Comment Actions
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
    
    fun playDraft() {
        voiceMessageManager.playPreview()
    }
    
    fun pauseDraft() {
        voiceMessageManager.pausePreview()
    }
    
    fun seekDraft(fraction: Float) {
        voiceMessageManager.seekPreview(fraction)
    }

    fun stopRecording(videoId: String, shouldSend: Boolean) {
        if (!shouldSend) {
            // Stop and Review
            voiceMessageManager.stopRecordingForReview()
        } else {
            // Send
            val currentState = voiceState.value
            var fileToSend: File? = null
            var duration = 0L
            
            if (currentState == conectate.com.presentation.messages.VoiceState.RECORDING || currentState == conectate.com.presentation.messages.VoiceState.PAUSED) {
                // Stop and get file
                val pair = voiceMessageManager.stopAndGetFile()
                fileToSend = pair.first
                duration = pair.second
            } else if (currentState == conectate.com.presentation.messages.VoiceState.REVIEWING) {
                // Get pre-recorded file
                fileToSend = voiceMessageManager.getRecordedFile()
                duration = recordingDuration.value
                voiceMessageManager.reset()
            }
            
            // Send logic
            if (fileToSend != null && duration > 800) {
                 viewModelScope.launch {
                     videoRepository.sendAudioComment(videoId, fileToSend)
                         .onSuccess { 
                             reloadVideo(videoId)
                             fetchComments(videoId) 
                             cancelReply()
                         }
                         .onFailure { e ->
                             android.util.Log.e("VideoFeedVM", "Error sending audio comment", e)
                         }
                 }
            } else {
                // Ensure reset if validation failed
                voiceMessageManager.reset()
            }
        }
    }

    fun playCommentAudio(comment: conectate.com.data.model.VideoComment) {
        val url = comment.mediaUrl ?: return
        
        if (_currentPlayingCommentId.value == comment.id) {
            stopAudio()
            return
        }

        stopAudio()
        _currentPlayingCommentId.value = comment.id
        
        mediaPlayer = MediaPlayer().apply {
            setDataSource(url)
            prepareAsync()
            setOnPreparedListener { 
                start()
                startProgressUpdate()
            }
            setOnCompletionListener { 
                stopAudio()
            }
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (mediaPlayer?.isPlaying == true) {
                val current = mediaPlayer?.currentPosition ?: 0
                val total = mediaPlayer?.duration ?: 1
                _audioProgress.value = current.toFloat() / total.toFloat()
                delay(100)
            }
        }
    }

    private fun stopAudio() {
        progressJob?.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        _currentPlayingCommentId.value = null
        _audioProgress.value = 0f
    }

    fun followUser(userId: String) {
        // Optimistic update - add to followed users immediately
        _followedUsers.value = _followedUsers.value + userId
        
        viewModelScope.launch {
            try {
                android.util.Log.d("VideoFeedVM", "followUser called for userId: $userId")
                val result = userRepository.followUser(userId)
                
                result.onSuccess {
                    android.util.Log.d("VideoFeedVM", "Successfully followed user: $userId")
                    // Keep the user in followed set
                    // Optionally reload following videos if on that tab
                    if (_selectedTab.value == 1) {
                        loadFollowingVideos()
                    }
                }.onFailure { e ->
                    android.util.Log.e("VideoFeedVM", "Failed to follow user: ${e.message}", e)
                    // Revert optimistic update on failure
                    _followedUsers.value = _followedUsers.value - userId
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoFeedVM", "Exception in followUser", e)
                // Revert optimistic update on exception
                _followedUsers.value = _followedUsers.value - userId
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
        voiceMessageManager.reset() // Clean up
    }

    private fun extractActiveMentionQuery(text: String): String? {
        val atIndex = text.lastIndexOf('@')
        if (atIndex < 0 || atIndex == text.lastIndex) return null

        val mentionCandidate = text.substring(atIndex + 1)
        if (mentionCandidate.any { it.isWhitespace() }) return null
        if (mentionCandidate.length > 30) return null

        return mentionCandidate.trim()
    }

    fun getVideoStats(videoId: String) = videoRepository.getVideoStatsFlow(videoId)
}
