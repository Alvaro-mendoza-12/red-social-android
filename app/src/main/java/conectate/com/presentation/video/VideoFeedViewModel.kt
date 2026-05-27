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
import kotlin.math.exp
import kotlin.math.ln
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
    private val viewedVideoIds = LinkedHashSet<String>()
    private val authorAffinity = mutableMapOf<String, Double>()
    private val tagAffinity = mutableMapOf<String, Double>()
    private val watchSecondsByVideo = mutableMapOf<String, Double>()
    private var activeViewingVideoId: String? = null
    private var activeViewingStartedAtMs: Long = 0L

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
                    _forYouVideos.value = rankForYouVideos(newVideos)
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
                        val mergedVideos = (_forYouVideos.value + newVideos).distinctBy { it.id }
                        _forYouVideos.value = rankForYouVideos(mergedVideos)
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

    fun onVideoFocused(video: Video) {
        val now = System.currentTimeMillis()
        if (activeViewingVideoId == video.id) return

        finishActiveWatchSession(now)
        activeViewingVideoId = video.id
        activeViewingStartedAtMs = now
        val shouldIncrementView = !viewedVideoIds.contains(video.id)
        rememberViewedVideo(video.id)
        recordVideoSignal(video, VIDEO_IMPRESSION_WEIGHT)

        if (shouldIncrementView) {
            incrementVideoViews(video.id)
        }
    }

    private fun finishActiveWatchSession(nowMs: Long = System.currentTimeMillis()) {
        val videoId = activeViewingVideoId ?: return
        val startedAt = activeViewingStartedAtMs.takeIf { it > 0L } ?: return
        val watchedSeconds = ((nowMs - startedAt).coerceAtLeast(0L) / 1000.0).coerceAtMost(90.0)
        if (watchedSeconds <= 0.25) {
            activeViewingVideoId = null
            activeViewingStartedAtMs = 0L
            return
        }

        val watchedVideo = findVideoById(videoId)
        if (watchedVideo != null) {
            val duration = watchedVideo.duration.takeIf { it > 0L }?.toDouble() ?: 30.0
            val completionRatio = (watchedSeconds / duration).coerceIn(0.0, 1.35)
            val watchWeight = when {
                watchedSeconds >= 8.0 || completionRatio >= 0.65 -> 1.30 + completionRatio
                watchedSeconds >= 3.0 -> 0.55 + completionRatio
                else -> 0.12
            }
            watchSecondsByVideo[videoId] = ((watchSecondsByVideo[videoId] ?: 0.0) + watchedSeconds)
                .coerceAtMost(duration * 3.0)
            recordVideoSignal(watchedVideo, watchWeight)
        }

        activeViewingVideoId = null
        activeViewingStartedAtMs = 0L
    }

    private fun recordVideoSignal(video: Video, weight: Double) {
        if (weight == 0.0) return
        applySignal(authorAffinity, video.userId, weight)
        video.tags.take(8).forEach { rawTag ->
            val tag = rawTag.trim().lowercase().removePrefix("#")
            if (tag.isNotBlank()) {
                applySignal(tagAffinity, tag, weight * 0.55)
            }
        }
    }

    private fun applySignal(target: MutableMap<String, Double>, key: String, delta: Double) {
        if (key.isBlank()) return
        val updated = ((target[key] ?: 0.0) * SIGNAL_DECAY + delta).coerceIn(-4.0, 10.0)
        if (kotlin.math.abs(updated) < 0.02) {
            target.remove(key)
        } else {
            target[key] = updated
        }
    }

    private fun rememberViewedVideo(videoId: String) {
        if (videoId.isBlank()) return
        viewedVideoIds.remove(videoId)
        viewedVideoIds.add(videoId)
        while (viewedVideoIds.size > MAX_VIEWED_VIDEO_MEMORY) {
            viewedVideoIds.remove(viewedVideoIds.first())
        }
    }

    private fun rankForYouVideos(videos: List<Video>): List<Video> {
        if (videos.size <= 2) return videos
        val nowMs = System.currentTimeMillis()
        val ranked = videos
            .distinctBy { it.id }
            .sortedByDescending { video -> scoreVideo(video, nowMs) }
        return diversifyVideosByAuthor(ranked)
    }

    private fun scoreVideo(video: Video, nowMs: Long): Double {
        val engagement = video.likeCount + (video.commentCount * 2.0) + (video.shareCount * 3.0) + (video.views * 0.04)
        val engagementScore = ln(1.0 + engagement.coerceAtLeast(0.0))
        val freshnessScore = exp(-ageHours(video.created_at, nowMs) / 42.0)
        val authorBoost = authorAffinity[video.userId] ?: 0.0
        val tagBoost = video.tags
            .take(8)
            .sumOf { tagAffinity[it.trim().lowercase().removePrefix("#")] ?: 0.0 }
            .coerceAtMost(6.0)
        val watchDuration = (video.duration.takeIf { it > 0L } ?: 30L).toDouble()
        val watchBoost = ((watchSecondsByVideo[video.id] ?: 0.0) / watchDuration)
            .coerceIn(0.0, 2.5)
        val seenPenalty = if (viewedVideoIds.contains(video.id)) 0.35 else 0.0
        val verifiedBoost = if (video.isVerified) 0.20 else 0.0
        val tieBreaker = ((video.id.hashCode() and 0xFFFF).toDouble() / 65_535.0) * 0.01

        return (engagementScore * 0.45) +
            (freshnessScore * 1.85) +
            (authorBoost * 0.55) +
            (tagBoost * 0.35) +
            (watchBoost * 0.65) +
            verifiedBoost -
            seenPenalty +
            tieBreaker
    }

    private fun diversifyVideosByAuthor(ranked: List<Video>): List<Video> {
        val remaining = ranked.toMutableList()
        val result = mutableListOf<Video>()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { candidate ->
                result.takeLast(2).none { it.userId == candidate.userId }
            } ?: remaining.first()
            result.add(next)
            remaining.remove(next)
        }
        return result
    }

    private fun ageHours(createdAt: String, nowMs: Long): Double {
        if (createdAt.isBlank()) return 72.0
        val createdMs = runCatching { java.time.Instant.parse(createdAt).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(createdAt).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { createdAt.toLong() }.getOrNull()
            ?: return 72.0
        return ((nowMs - createdMs).coerceAtLeast(0L) / 3_600_000.0)
    }

    fun toggleLike(video: Video) {
        val videoId = video.id
        val snapshotForYou = _forYouVideos.value
        val snapshotFollowing = _followingVideos.value
        val snapshotSearch = _searchResults.value

        val current = findVideoById(videoId) ?: video
        val newLikedState = !current.likedByMe
        recordVideoSignal(current, if (newLikedState) VIDEO_LIKE_WEIGHT else -VIDEO_LIKE_WEIGHT)

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
        recordVideoSignal(current, if (newSharedState) VIDEO_SHARE_WEIGHT else -VIDEO_SHARE_WEIGHT)

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
        recordVideoSignal(video, VIDEO_COMMENT_OPEN_WEIGHT)
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
         findVideoById(videoId)?.let { recordVideoSignal(it, VIDEO_COMMENT_WEIGHT) }
         
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

    private companion object {
        private const val SIGNAL_DECAY = 0.992
        private const val MAX_VIEWED_VIDEO_MEMORY = 900
        private const val VIDEO_IMPRESSION_WEIGHT = 0.08
        private const val VIDEO_LIKE_WEIGHT = 1.35
        private const val VIDEO_SHARE_WEIGHT = 1.80
        private const val VIDEO_COMMENT_OPEN_WEIGHT = 0.50
        private const val VIDEO_COMMENT_WEIGHT = 1.55
    }

    override fun onCleared() {
        super.onCleared()
        finishActiveWatchSession()
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
