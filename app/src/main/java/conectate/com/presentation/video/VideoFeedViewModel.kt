package conectate.com.presentation.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Post
import conectate.com.data.model.Video
import conectate.com.data.repository.PostRepository
import conectate.com.data.repository.VideoRepository
import conectate.com.data.repository.VideoFeedRealtimeEvent
import conectate.com.data.repository.AlgorithmRepository
import conectate.com.data.repository.ExplicitPreferenceChange
import conectate.com.data.recommendations.OpaqueRecommendationCursor
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
import conectate.com.util.UbuntuMedia
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import conectate.com.presentation.videos.editVideoCommentImmediately
import conectate.com.presentation.videos.mergeLatestVideoComments
import conectate.com.presentation.videos.reactToVideoCommentImmediately
import conectate.com.presentation.videos.removeVideoCommentImmediately
import javax.inject.Inject
import java.util.UUID

private const val VIDEO_AUTHORITATIVE_REQUEST_TIMEOUT_MS = 5_000L
private const val VIDEO_FEED_UI_WATCHDOG_MS = 12_000L
private const val VIDEO_ALGORITHM_UNDO_WINDOW_MS = 10_000L

data class VideoAlgorithmUndoOffer(
    val id: String,
    val message: String,
    val expiresAtElapsedMs: Long,
    val change: ExplicitPreferenceChange,
)

@HiltViewModel
class VideoFeedViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val postRepository: PostRepository,
    private val voiceMessageManager: conectate.com.presentation.messages.VoiceMessageManager, // Injected
    private val authService: AuthService,
    val userRepository: conectate.com.data.repository.UserRepository,
    private val shareRepository: conectate.com.data.repository.ShareRepository,
    private val liveRepository: conectate.com.data.repository.LiveRepository,
    private val socializationRepository: conectate.com.data.repository.SocializationRepository,
    private val algorithmRepository: AlgorithmRepository,
    appSettingsPreferences: conectate.com.data.local.prefs.AppSettingsPreferences,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    
    // Two separate lists to avoid reloading when switching tabs
    private val _forYouVideos = VideoFeedPageStore()
    val forYouFeed: StateFlow<VideoFeedSnapshot> = _forYouVideos.snapshot

    private val _followingVideos = MutableStateFlow<List<Video>>(emptyList())
    val followingVideos: StateFlow<List<Video>> = _followingVideos.asStateFlow()
    private val _followingFeedError = MutableStateFlow<String?>(null)
    val followingFeedError = _followingFeedError.asStateFlow()
    private val profileFeedOwnerId: String? = savedStateHandle.get<String>("profileId")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    private val profileInitialIndex = (savedStateHandle.get<Int>("initialIndex") ?: 0).coerceAtLeast(0)
    val isProfileFeed: Boolean = profileFeedOwnerId != null
    private val _profileVideos = MutableStateFlow<List<Video>>(emptyList())
    val profileVideos: StateFlow<List<Video>> = _profileVideos.asStateFlow()
    private val _socialHighlights = MutableStateFlow<List<Post>>(emptyList())
    val socialHighlights: StateFlow<List<Post>> = _socialHighlights.asStateFlow()
    private val _recommendedPeople = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val recommendedPeople: StateFlow<List<conectate.com.data.model.User>> = _recommendedPeople.asStateFlow()
    private val _socializationCards = MutableStateFlow<List<conectate.com.data.model.SocializationCard>>(emptyList())
    val socializationCards: StateFlow<List<conectate.com.data.model.SocializationCard>> = _socializationCards.asStateFlow()
    val activeStreams = liveRepository.activeStreams
    val showLiveRecommendations = appSettingsPreferences.livesInVideoFeedEnabled
    val dataSaverEnabled = appSettingsPreferences.dataSaverEnabled
    val videoAutoplayOnMobile = appSettingsPreferences.videoAutoplayOnMobile
    private val _soundVideos = MutableStateFlow<List<Video>>(emptyList())
    val soundVideos: StateFlow<List<Video>> = _soundVideos.asStateFlow()
    private val _isSoundVideosLoading = MutableStateFlow(false)
    val isSoundVideosLoading: StateFlow<Boolean> = _isSoundVideosLoading.asStateFlow()
    private val _soundVideosError = MutableStateFlow<String?>(null)
    val soundVideosError: StateFlow<String?> = _soundVideosError.asStateFlow()
    private var soundVideosRequest: Job? = null
    private var soundVideosRequestId = 0L

    // Avoid rendering the empty-feed message for one frame while init starts
    // the first network/cache request.
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    val feedError: StateFlow<String?> = videoRepository.videoFeedError

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
    private val playbackViewTracker = RealPlaybackViewTracker()

    private var currentPage = 0
    private val pageSize = 20
    private var isLastPage = false
    private var forYouJob: Job? = null
    private var loadMoreJob: Job? = null
    private var followedUsersJob: Job? = null
    private var videoFollowState = VideoFollowState()
    private var supplementalFeedJob: Job? = null
    private var authoritativeFeedActive = false
    private var authoritativeCursor: OpaqueRecommendationCursor? = null
    private var authoritativeExplanationsEnabled = false
    private var authoritativeRankingVersion: String? = null
    private var feedSessionId: String = UUID.randomUUID().toString()
    private var feedWasBackgrounded = false
    private var feedSessionStartedAtElapsedMs = 0L
    private val feedSessionRefreshDebounceMs = 750L
    private val authoritativeExplanations = mutableMapOf<String, List<String>>()
    private val profilePageSize = ProfileVideoFeedPaging.DefaultPageSize
    private var profileFirstOffset = 0
    private var profileNextOffset = 0
    private var isProfileLastPage = false
    private var profilePageRequestInFlight = false
    private val _profilePrependCount = MutableStateFlow(0)
    val profilePrependCount: StateFlow<Int> = _profilePrependCount.asStateFlow()
    private val likedDebounceJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val shareRequestsInFlight = mutableSetOf<String>()
    private val focusedVideoIds = mutableSetOf<String>()
    private val focusedVideosById = linkedMapOf<String, Video>()
    private var previousInitialForYouOrder: List<String> = emptyList()
    private var lastOrderedForYouSessionId: String? = null

    fun ensureVideoInFeed(videoId: String) {
        val cleanId = videoId.trim()
        if (cleanId.isBlank() || _forYouVideos.value.any { it.id == cleanId }) return
        if (!focusedVideoIds.add(cleanId)) return
        val generation = accountGeneration
        viewModelScope.launch {
            videoRepository.getVideoById(cleanId)
                .onSuccess { video ->
                    if (generation != accountGeneration) return@onSuccess
                    focusedVideosById[cleanId] = video
                    _forYouVideos.value = mergeFocusedVideoPage(
                        focusedVideos = focusedVideosById.values,
                        loadedPage = _forYouVideos.value,
                    )
                }
                .onFailure { focusedVideoIds.remove(cleanId) }
        }
    }

    private fun applyRealtimeFeedEvent(event: VideoFeedRealtimeEvent) {
        when (event.action) {
            "deleted" -> {
                focusedVideoIds.remove(event.videoId)
                focusedVideosById.remove(event.videoId)
                _forYouVideos.value = _forYouVideos.value.filterNot { it.id.equals(event.videoId, true) }
                _followingVideos.value = _followingVideos.value.filterNot { it.id.equals(event.videoId, true) }
                _searchResults.value = _searchResults.value.filterNot { it.id.equals(event.videoId, true) }
                _profileVideos.value = _profileVideos.value.filterNot { it.id.equals(event.videoId, true) }
                _soundVideos.value = _soundVideos.value.filterNot { it.id.equals(event.videoId, true) }
                if (_selectedVideoForComments.value?.id.equals(event.videoId, true)) {
                    _commentsSheetOpen.value = false
                    _selectedVideoForComments.value = null
                    _comments.value = emptyList()
                }
            }
            "created" -> {
                if (
                    _forYouVideos.value.any { it.id.equals(event.videoId, true) } ||
                    focusedVideosById.containsKey(event.videoId)
                ) return
                val generation = accountGeneration
                viewModelScope.launch {
                    videoRepository.getVideoById(event.videoId).onSuccess { video ->
                        if (generation != accountGeneration || !video.isPublic) return@onSuccess
                        if (_forYouVideos.value.none { it.id.equals(video.id, true) }) {
                            // Append instead of moving the page under the viewer's finger.
                            // The next feed session will place it using the full algorithm.
                            _forYouVideos.value = _forYouVideos.value + video
                        }
                        if (
                            _followedUsers.value.any { it.equals(video.userId, true) } &&
                            _followingVideos.value.none { it.id.equals(video.id, true) }
                        ) {
                            _followingVideos.value = _followingVideos.value + video
                        }
                        if (
                            profileFeedOwnerId?.equals(video.userId, true) == true &&
                            _profileVideos.value.none { it.id.equals(video.id, true) }
                        ) {
                            _profileVideos.value = _profileVideos.value + video
                        }
                    }
                }
            }
        }
    }

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
    private val _algorithmActionMessage = MutableStateFlow<String?>(null)
    val algorithmActionMessage: StateFlow<String?> = _algorithmActionMessage.asStateFlow()
    private val _algorithmUndoOffer = MutableStateFlow<VideoAlgorithmUndoOffer?>(null)
    val algorithmUndoOffer: StateFlow<VideoAlgorithmUndoOffer?> = _algorithmUndoOffer.asStateFlow()
    private val activeAlgorithmBlocks = mutableSetOf<VideoFeedbackBlock>()
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

    private val _currentUserId = MutableStateFlow(
        authService.getCurrentUserId()?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    )
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()
    private var activeAccountId: String? = _currentUserId.value
    private var accountGeneration: Long = 0L

    // Follow Status Tracking
    private val _followedUsers = MutableStateFlow<Set<String>>(emptySet())
    val followedUsers: StateFlow<Set<String>> = _followedUsers.asStateFlow()
    private val _videoDownloadsInProgress = MutableStateFlow<Set<String>>(emptySet())
    val videoDownloadsInProgress: StateFlow<Set<String>> = _videoDownloadsInProgress.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var commentsJob: Job? = null

    init {
        viewModelScope.launch {
            authService.currentSession
                .map { session ->
                    session?.user?.id?.trim()?.lowercase() ?: authService.getCurrentUserId()
                }
                .distinctUntilChanged()
                .collect { accountId ->
                    if (accountId != activeAccountId) handleAccountChanged(accountId)
                }
        }
        viewModelScope.launch {
            videoRepository.videoFeedEvents.collect(::applyRealtimeFeedEvent)
        }
        if (isProfileFeed) {
            loadProfileVideos(checkNotNull(profileFeedOwnerId))
        } else {
            loadForYouVideos()
            scheduleSupplementalFeedContent()
        }
    }

    private fun handleAccountChanged(accountId: String?) {
        activeAccountId = accountId
        accountGeneration++
        _algorithmUndoOffer.value = null
        activeAlgorithmBlocks.clear()
        _currentUserId.value = accountId
        forYouJob?.cancel()
        loadMoreJob?.cancel()
        loadMoreJob = null
        followedUsersJob?.cancel()
        followedUsersJob = null
        supplementalFeedJob?.cancel()
        supplementalFeedJob = null
        searchJob?.cancel()
        mentionSearchJob?.cancel()
        commentsJob?.cancel()
        progressJob?.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
        currentPage = 0
        isLastPage = false
        resetAuthoritativeFeedState()
        feedSessionId = UUID.randomUUID().toString()
        focusedVideoIds.clear()
        focusedVideosById.clear()
        _forYouVideos.clear()
        _isRefreshing.value = false
        _followingVideos.value = emptyList()
        _followingFeedError.value = null
        _socialHighlights.value = emptyList()
        _recommendedPeople.value = emptyList()
        _socializationCards.value = emptyList()
        videoFollowState = VideoFollowState()
        _followedUsers.value = emptySet()
        clearSoundVideos()
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _isSearchMode.value = false
        _isSearching.value = false
        _commentsSheetOpen.value = false
        _selectedVideoForComments.value = null
        _comments.value = emptyList()
        _mentionSuggestions.value = emptyList()
        _currentPlayingCommentId.value = null
        _audioProgress.value = 0f
        _profileVideos.value = emptyList()
        _isLoading.value = true
        likedDebounceJobs.values.forEach { it.cancel() }
        likedDebounceJobs.clear()
        shareRequestsInFlight.clear()
        if (isProfileFeed) {
            loadProfileVideos(checkNotNull(profileFeedOwnerId))
        } else {
            loadForYouVideos()
            scheduleSupplementalFeedContent()
        }
    }

    private fun scheduleSupplementalFeedContent() {
        supplementalFeedJob?.cancel()
        val generation = accountGeneration
        supplementalFeedJob = viewModelScope.launch {
            // Give the visible video request first use of the connection and
            // database. These cards are useful, but they must never delay the
            // first playable frame.
            _isLoading.filter { loading -> !loading }.first()
            if (generation != accountGeneration) return@launch
            loadSocialHighlights()
            loadRecommendedPeople()
            loadSocializationCards()
        }
    }

    private fun loadSocializationCards() {
        val generation = accountGeneration
        viewModelScope.launch {
            socializationRepository.loadCards(surface = "video", placement = "after_12", limit = 4)
                .onSuccess { response ->
                    if (generation != accountGeneration) return@onSuccess
                    _socializationCards.value = if (
                        response.enabled && response.safeguards.serverFiltered &&
                        response.safeguards.consentRequired && !response.safeguards.automaticContact
                    ) response.items
                        .filter { it.action.type in setOf("OPEN_PROFILE", "OPEN_COMMUNITY", "OPEN_LIVE") }
                        .distinctBy { it.id }
                    else emptyList()
                }
                .onFailure {
                    if (generation == accountGeneration) _socializationCards.value = emptyList()
                }
        }
    }

    fun dismissSocializationCard(cardId: String) {
        _socializationCards.value = _socializationCards.value.filterNot { it.id == cardId }
    }

    private fun loadProfileVideos(profileId: String, reset: Boolean = true) {
        if (profilePageRequestInFlight || (!reset && isProfileLastPage)) return
        val requestedOffset = if (reset) {
            ProfileVideoFeedPaging.initialPage(profileInitialIndex, profilePageSize).offset
        } else profileNextOffset
        profilePageRequestInFlight = true
        viewModelScope.launch {
            try {
                if (reset) _isLoading.value = true
                videoRepository.getUserVideosPage(
                    userId = profileId,
                    offset = requestedOffset,
                    limit = profilePageSize
                ).onSuccess { page ->
                    if (reset) {
                        _profileVideos.value = page
                    } else {
                        val merged = (_profileVideos.value + page).distinctBy { it.id }
                        val window = ProfileVideoFeedPaging.boundedWindow(
                            items = merged,
                            pageSize = profilePageSize,
                            dropFromStart = true
                        )
                        _profileVideos.value = window.items
                        if (window.removedFromStart > 0) {
                            profileFirstOffset += window.removedFromStart
                            _profilePrependCount.value = -window.removedFromStart
                        }
                    }
                    if (reset) {
                        profileFirstOffset = requestedOffset
                        _profilePrependCount.value = 0
                    }
                    profileNextOffset = requestedOffset + page.size
                    isProfileLastPage = page.size < profilePageSize
                    loadFollowedUsers()
                }.onFailure { error ->
                    android.util.Log.e("VideoFeedVM", "No se pudo cargar el feed del perfil", error)
                }
            } catch (error: Exception) {
                android.util.Log.e("VideoFeedVM", "No se pudo cargar el feed del perfil", error)
            } finally {
                profilePageRequestInFlight = false
                if (reset) _isLoading.value = false
            }
        }
    }

    fun loadMoreProfileVideos() {
        val profileId = profileFeedOwnerId ?: return
        loadProfileVideos(profileId, reset = false)
    }

    fun loadPreviousProfileVideos() {
        val profileId = profileFeedOwnerId ?: return
        if (profilePageRequestInFlight || profileFirstOffset <= 0) return

        val request = ProfileVideoFeedPaging.previousPage(profileFirstOffset, profilePageSize) ?: return

        profilePageRequestInFlight = true
        viewModelScope.launch {
            try {
                videoRepository.getUserVideosPage(
                    userId = profileId,
                    offset = request.offset,
                    limit = request.limit
                ).onSuccess { page ->
                    val existingIds = _profileVideos.value.asSequence().map { it.id }.toHashSet()
                    val newVideos = page.filterNot { it.id in existingIds }
                    if (newVideos.isNotEmpty()) {
                        val window = ProfileVideoFeedPaging.boundedWindow(
                            items = newVideos + _profileVideos.value,
                            pageSize = profilePageSize,
                            dropFromStart = false
                        )
                        _profileVideos.value = window.items
                        _profilePrependCount.value = newVideos.size
                        if (window.removedFromEnd > 0) {
                            profileNextOffset = request.offset + window.items.size
                            isProfileLastPage = false
                        }
                    }
                    profileFirstOffset = request.offset
                    loadFollowedUsers()
                }.onFailure { error ->
                    android.util.Log.e("VideoFeedVM", "No se pudo cargar la página anterior del perfil", error)
                }
            } finally {
                profilePageRequestInFlight = false
            }
        }
    }

    fun consumeProfilePrepend() {
        _profilePrependCount.value = 0
    }

    private fun loadRecommendedPeople() {
        val generation = accountGeneration
        viewModelScope.launch {
            val currentId = _currentUserId.value?.trim()?.lowercase().orEmpty()
            if (currentId.isBlank()) return@launch
            userRepository.getUsersByFollowerThreshold(0).onSuccess { users ->
                val followed = userRepository.getFollowedUserIds(users.map { it.id }).getOrDefault(emptySet())
                if (generation != accountGeneration || currentId != activeAccountId) return@onSuccess
                _recommendedPeople.value = users
                    .filter { it.id.isNotBlank() && it.id.trim().lowercase() != currentId }
                    .filterNot { it.isSuspendedNow || followed.contains(it.id.trim().lowercase()) }
                    .distinctBy { it.id.trim().lowercase() }
                    .take(12)
            }
        }
    }

    fun followRecommendedUser(user: conectate.com.data.model.User) {
        _recommendedPeople.value = _recommendedPeople.value.filterNot { it.id == user.id }
        viewModelScope.launch {
            userRepository.followUser(user.id).onFailure {
                _recommendedPeople.value = (_recommendedPeople.value + user).distinctBy { it.id }.take(12)
            }
        }
    }

    private fun loadSocialHighlights() {
        val generation = accountGeneration
        val accountId = activeAccountId
        viewModelScope.launch {
            runCatching {
                postRepository.getGlobalPosts(limit = 8, mode = "discover").collect { posts ->
                    if (generation != accountGeneration) return@collect
                    _socialHighlights.value = posts
                        .filter { it.id.isNotBlank() && it.userId.trim().lowercase() != accountId }
                        .distinctBy { it.id }
                        .take(8)
                }
            }.onFailure { error ->
                android.util.Log.w("VideoFeedVM", "Social highlights unavailable", error)
                if (generation == accountGeneration) _socialHighlights.value = emptyList()
            }
        }
    }

    private fun loadFollowedUsers() {
        followedUsersJob?.cancel()
        val generation = accountGeneration
        val requestRevision = videoFollowState.revision
        followedUsersJob = viewModelScope.launch {
            try {
                val allVideos = _forYouVideos.value + _followingVideos.value + _profileVideos.value
                val userIds = allVideos
                    .map { it.userId.trim().lowercase() }
                    .filter { it.isNotBlank() }
                    .distinct()

                // One batched request replaces one network round-trip per
                // creator. This runs after every page append, so the former
                // N+1 loop noticeably delayed the feed on real connections.
                val followed = userRepository.getFollowedUserIds(userIds).getOrThrow()
                if (generation == accountGeneration) {
                    publishFollowState(videoFollowState.reconcile(userIds, followed, requestRevision))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VideoFeedVM", "Error loading followed users", e)
            }
        }
    }
    
    // Public function to refresh follow status (e.g., after unfollowing from profile)
    fun refreshFollowStatus() {
        loadFollowedUsers()
    }

    fun refreshLives() {
        viewModelScope.launch { liveRepository.refreshActiveStreams() }
    }

    fun refreshFeed() {
        when {
            isProfileFeed -> loadProfileVideos(checkNotNull(profileFeedOwnerId))
            _selectedTab.value == 0 -> startNewForYouFeedSession()
            else -> {
                _isRefreshing.value = true
                loadFollowingVideos()
            }
        }
    }

    fun onFeedBackgrounded() {
        if (!isProfileFeed) feedWasBackgrounded = true
    }

    fun onFeedResumed() {
        if (!isProfileFeed && feedWasBackgrounded) {
            feedWasBackgrounded = false
            startNewForYouFeedSession()
        }
    }

    private fun startNewForYouFeedSession() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - feedSessionStartedAtElapsedMs < feedSessionRefreshDebounceMs) return
        feedSessionStartedAtElapsedMs = now
        feedSessionId = UUID.randomUUID().toString()
        _isRefreshing.value = true
        focusedVideoIds.clear()
        focusedVideosById.clear()
        resetAuthoritativeFeedState()
        // Keep the last playable page mounted while the new session is
        // revalidated. Clearing it here produced a black TikTok-style feed on
        // every resume/reselection even though a valid snapshot was available.
        _isLoading.value = true
        loadForYouVideos()
    }

    fun recoverFeedAfterAppUpdate() {
        viewModelScope.launch {
            repeat(3) { attempt ->
                if (authService.ensureValidAccessToken().isSuccess) {
                    refreshFeed()
                    refreshLives()
                    return@launch
                }
                delay(500L * (attempt + 1))
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }

        val generation = accountGeneration
        searchJob = viewModelScope.launch {
            delay(500) // Debounce search
            try {
                _isSearching.value = true
                videoRepository.searchVideos(query).onSuccess { videos ->
                    if (generation != accountGeneration) return@onSuccess
                    _searchResults.value = videos
                    _isSearching.value = false
                }.onFailure {
                    if (generation == accountGeneration) _isSearching.value = false
                }
            } catch (e: Exception) {
                if (generation == accountGeneration) _isSearching.value = false
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

    private fun loadForYouVideos(forceLegacy: Boolean = false) {
        forYouJob?.cancel()
        loadMoreJob?.cancel()
        loadMoreJob = null
        val generation = accountGeneration
        val requestSessionId = feedSessionId
        forYouJob = viewModelScope.launch {
            val uiWatchdog = launch {
                delay(VIDEO_FEED_UI_WATCHDOG_MS)
                if (
                    generation == accountGeneration &&
                    requestSessionId == feedSessionId &&
                    _forYouVideos.value.isEmpty()
                ) {
                    // A broken recommendation or catalog endpoint must not
                    // keep the first video screen in a permanent spinner.
                    _isLoading.value = false
                }
            }
            try {
                _isLoading.value = true
                currentPage = 0
                isLastPage = false
                resetAuthoritativeFeedState()
                val cachedVideos = videoRepository.getCachedPublicVideos(requestSessionId)
                if (_forYouVideos.value.isEmpty() && cachedVideos.isNotEmpty() && generation == accountGeneration) {
                    mergeInitialForYouPage(
                        focusedVideos = focusedVideosById.values,
                        loadedPage = cachedVideos,
                        sessionId = requestSessionId,
                    )
                    isLastPage = cachedVideos.size < pageSize
                }
                // The public catalog is the fastest first-screen fallback.
                // Only wait for the personalized page when cached video is
                // already visible, so a cold install never sits on a spinner.
                if (!forceLegacy && _forYouVideos.value.isNotEmpty()) {
                    val page = withTimeoutOrNull(VIDEO_AUTHORITATIVE_REQUEST_TIMEOUT_MS) {
                        algorithmRepository.loadVideoRecommendationPage(
                            cursor = null,
                            limit = pageSize,
                            refreshSeed = requestSessionId,
                        )
                    }?.getOrNull()
                    if (
                        page != null &&
                        VideoAlgorithmFeedbackPolicy.shouldUseAuthoritativeInitialPage(page.items.size)
                    ) {
                        if (generation != accountGeneration || requestSessionId != feedSessionId) return@launch
                        authoritativeFeedActive = true
                        authoritativeCursor = page.nextCursor
                        authoritativeExplanationsEnabled = page.explanationsEnabled
                        authoritativeRankingVersion = page.rankingVersion
                        authoritativeExplanations.putAll(page.explanationsByCandidateId)
                        mergeInitialForYouPage(
                            focusedVideos = focusedVideosById.values,
                            loadedPage = page.items,
                            sessionId = requestSessionId,
                        )
                        isLastPage = !page.hasMore
                        if (generation == accountGeneration && requestSessionId == feedSessionId) _isLoading.value = false
                        loadFollowedUsers()
                        return@launch
                    }
                }
                try {
                    videoRepository.getVideos(
                        offset = 0,
                        limit = pageSize,
                        random = true,
                        feedSessionId = requestSessionId,
                        emitLocalSnapshotFirst = true,
                    ).collect { newVideos ->
                        if (generation != accountGeneration || requestSessionId != feedSessionId) return@collect
                        // Ignore a provisional empty disk-cache emission. The
                        // same flow will emit the network catalog next; keeping
                        // loading active prevents the empty-state flash.
                        if (newVideos.isEmpty()) return@collect
                        mergeInitialForYouPage(
                            focusedVideosById.values,
                            newVideos,
                            requestSessionId,
                        )
                        isLastPage = newVideos.size < pageSize
                    }
                } finally {
                    if (generation == accountGeneration && requestSessionId == feedSessionId) _isLoading.value = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VideoFeedVM", "Error loading for you videos", e)
                if (generation == accountGeneration && requestSessionId == feedSessionId) _isLoading.value = false
            } finally {
                uiWatchdog.cancel()
                if (generation == accountGeneration && requestSessionId == feedSessionId) {
                    _isRefreshing.value = false
                }
            }
        }
    }

    fun loadMoreVideos() {
        if (_isLoading.value || isLastPage || loadMoreJob?.isActive == true) return

        val generation = accountGeneration
        val requestSessionId = feedSessionId
        loadMoreJob = viewModelScope.launch {
            var requestedLegacyPage: Int? = null
            try {
                _isLoading.value = true
                if (authoritativeFeedActive) {
                    val cursor = authoritativeCursor
                    if (cursor == null) {
                        isLastPage = true
                        return@launch
                    }
                    val page = algorithmRepository.loadVideoRecommendationPage(cursor = cursor, limit = pageSize)
                        .getOrElse {
                            // A legacy page must never be appended to a server
                            // page. Replace the entire list with one coherent
                            // legacy feed after an authoritative page failure.
                            loadForYouVideos(forceLegacy = true)
                            return@launch
                        }
                    if (generation != accountGeneration || requestSessionId != feedSessionId) return@launch
                    authoritativeCursor = page.nextCursor
                    authoritativeExplanations.putAll(page.explanationsByCandidateId)
                    val orderedPage = orderVideosForFeedSession(
                        videos = page.items,
                        accountId = activeAccountId,
                        sessionId = requestSessionId,
                        preserveSourceOrder = true,
                    )
                    _forYouVideos.value = filterActiveAlgorithmBlocks(
                        (_forYouVideos.value + orderedPage).distinctBy { it.id })
                    isLastPage = !page.hasMore
                    loadFollowedUsers()
                    return@launch
                }
                currentPage++
                requestedLegacyPage = currentPage
                val offset = currentPage * pageSize
                videoRepository.getVideos(
                    offset = offset,
                    limit = pageSize,
                    random = true,
                    feedSessionId = requestSessionId,
                    emitLocalSnapshotFirst = false,
                ).collect { newVideos ->
                    if (generation != accountGeneration || requestSessionId != feedSessionId) return@collect
                    if (newVideos.isEmpty()) {
                        isLastPage = true
                    } else {
                        val orderedPage = orderVideosForFeedSession(
                            videos = newVideos,
                            accountId = activeAccountId,
                            sessionId = requestSessionId,
                        )
                        _forYouVideos.value = filterActiveAlgorithmBlocks(
                            (_forYouVideos.value + orderedPage).distinctBy { it.id })
                        if (newVideos.size < pageSize) isLastPage = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                requestedLegacyPage?.let { failedPage ->
                    if (currentPage == failedPage) currentPage = (failedPage - 1).coerceAtLeast(0)
                }
                android.util.Log.e("VideoFeedVM", "Error loading more videos", e)
            } finally {
                if (
                    generation == accountGeneration &&
                    requestSessionId == feedSessionId &&
                    forYouJob?.isActive != true
                ) {
                    _isLoading.value = false
                }
            }
        }
    }

    fun explainVideo(video: Video): List<String> {
        if (!authoritativeFeedActive) return emptyList()
        return authoritativeExplanations[video.id]
            ?: if (authoritativeExplanationsEnabled) {
                listOf("Mi algoritmo seleccionó este video en el servidor.")
            } else {
                listOf(
                    "Mi algoritmo ordenó este video en el servidor" +
                        authoritativeRankingVersion?.let { " ($it)" }.orEmpty() +
                        "; las explicaciones detalladas están desactivadas.",
                )
            }
    }

    fun excludeVideoFromAlgorithm(video: Video) = applyVideoAlgorithmRule(
        video = video,
        dimension = "content",
        subjectKey = video.id,
        subjectLabel = "Este video",
        successMessage = "Este video dejará de recomendarse.",
    )

    fun excludeVideoCreatorFromAlgorithm(video: Video) = applyVideoAlgorithmRule(
        video = video,
        dimension = "creator",
        subjectKey = video.userId,
        subjectLabel = video.userName.ifBlank { "Este creador" },
        successMessage = "Verás menos videos de ${video.userName.ifBlank { "este creador" }}.",
    )

    fun excludeVideoFormatFromAlgorithm(video: Video) = applyVideoAlgorithmRule(
        video = video,
        dimension = "format",
        subjectKey = VideoAlgorithmFeedbackPolicy.formatKey(video.duration),
        subjectLabel = if (video.duration in 1..60) "Videos cortos" else "Videos largos",
        successMessage = "Este tipo de video tendrá menos prioridad.",
        score = -30,
    )

    fun excludeVideoTopicFromAlgorithm(video: Video, topic: String) {
        val normalized = VideoAlgorithmFeedbackPolicy.topicKey(topic)
        if (normalized == null) {
            _algorithmActionMessage.value = "Elige un tema válido."
            return
        }
        applyVideoAlgorithmRule(
            video = video,
            dimension = "topic",
            subjectKey = normalized,
            subjectLabel = topic.trim(),
            successMessage = "El tema ${topic.trim()} dejará de recomendarse.",
        )
    }

    fun consumeAlgorithmActionMessage() {
        _algorithmActionMessage.value = null
    }

    fun consumeAlgorithmUndoOffer(id: String) {
        if (_algorithmUndoOffer.value?.id == id) _algorithmUndoOffer.value = null
    }

    fun undoAlgorithmPreference(id: String) {
        val offer = _algorithmUndoOffer.value?.takeIf { it.id == id } ?: return
        _algorithmUndoOffer.value = null
        if (android.os.SystemClock.elapsedRealtime() > offer.expiresAtElapsedMs) return
        val generation = accountGeneration
        viewModelScope.launch {
            algorithmRepository.undoExplicitPreference(offer.change).onSuccess {
                if (generation != accountGeneration) return@onSuccess
                val block = VideoFeedbackBlock(offer.change.dimension, offer.change.subjectKey)
                if (offer.change.previous?.score == -100) activeAlgorithmBlocks.add(block)
                else activeAlgorithmBlocks.remove(block)
                _algorithmActionMessage.value = "Preferencia revertida."
                loadForYouVideos()
            }.onFailure {
                if (generation != accountGeneration) return@onFailure
                _algorithmActionMessage.value =
                    "No se pudo deshacer porque la preferencia cambió o no hay conexión. Revísala en Mi algoritmo."
            }
        }
    }

    private fun applyVideoAlgorithmRule(
        video: Video,
        dimension: String,
        subjectKey: String,
        subjectLabel: String,
        successMessage: String,
        score: Int = -100,
    ) {
        val generation = accountGeneration
        viewModelScope.launch {
            algorithmRepository.applyExplicitPreference(
                surface = "video",
                dimension = dimension,
                subjectKey = subjectKey,
                subjectLabel = subjectLabel,
                score = score,
            ).onSuccess { change ->
                if (generation != accountGeneration) return@onSuccess
                val block = VideoFeedbackBlock(change.dimension, change.subjectKey)
                if (score == -100) activeAlgorithmBlocks.add(block)
                else activeAlgorithmBlocks.remove(block)
                focusedVideoIds.remove(video.id)
                focusedVideosById.remove(video.id)
                _forYouVideos.value = filterActiveAlgorithmBlocks(
                    _forYouVideos.value.filterNot { it.id == video.id })
                _algorithmUndoOffer.value = VideoAlgorithmUndoOffer(
                    id = UUID.randomUUID().toString(),
                    message = successMessage,
                    expiresAtElapsedMs = android.os.SystemClock.elapsedRealtime() +
                        VIDEO_ALGORITHM_UNDO_WINDOW_MS,
                    change = change,
                )
                loadForYouVideos()
            }.onFailure {
                if (generation != accountGeneration) return@onFailure
                _algorithmActionMessage.value =
                    "No se pudo guardar en Mi algoritmo. Revisa tu conexión e inténtalo nuevamente."
            }
        }
    }

    private fun resetAuthoritativeFeedState() {
        authoritativeFeedActive = false
        authoritativeCursor = null
        authoritativeExplanationsEnabled = false
        authoritativeRankingVersion = null
        authoritativeExplanations.clear()
    }

    private fun mergeInitialForYouPage(
        focusedVideos: Collection<Video>,
        loadedPage: Iterable<Video>,
        sessionId: String,
    ) {
        val previousOrder = if (lastOrderedForYouSessionId == sessionId) {
            emptyList()
        } else {
            previousInitialForYouOrder
        }
        val orderedPage = orderVideosForFeedSession(
            videos = loadedPage,
            accountId = activeAccountId,
            sessionId = sessionId,
            previousOrder = previousOrder,
            preserveSourceOrder = authoritativeFeedActive,
        )
        val merged = filterActiveAlgorithmBlocks(mergeFocusedVideoPage(focusedVideos, orderedPage))
        if (lastOrderedForYouSessionId != sessionId) {
            previousInitialForYouOrder = merged.map { it.id.trim().lowercase() }
            lastOrderedForYouSessionId = sessionId
        }
        _forYouVideos.publishInitial(merged, sessionId)
    }

    private fun filterActiveAlgorithmBlocks(videos: List<Video>): List<Video> =
        if (activeAlgorithmBlocks.isEmpty()) videos
        else videos.filterNot { video -> activeAlgorithmBlocks.any { it.matches(video) } }

    private fun loadFollowingVideos() {
        val generation = accountGeneration
        viewModelScope.launch {
            val uiWatchdog = launch {
                delay(VIDEO_FEED_UI_WATCHDOG_MS)
                if (generation == accountGeneration && _followingVideos.value.isEmpty()) {
                    _isLoading.value = false
                }
            }
            try {
                _isLoading.value = true
                _followingFeedError.value = null
                videoRepository.getFollowingVideos(failOnError = true).collect { videos ->
                    if (generation == accountGeneration) _followingVideos.value = videos
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (generation == accountGeneration) _followingFeedError.value =
                    "No pudimos actualizar los videos de tus seguidos. Intenta nuevamente."
                android.util.Log.e("VideoFeedVM", "Error loading following videos", e)
            } finally {
                uiWatchdog.cancel()
                if (generation == accountGeneration) {
                    _isLoading.value = false
                    _isRefreshing.value = false
                }
            }
        }
    }

    fun onVideoPlaybackSample(
        videoId: String?,
        isActuallyPlaying: Boolean,
        elapsedRealtimeMs: Long
    ) {
        if (!playbackViewTracker.sample(videoId, isActuallyPlaying, elapsedRealtimeMs)) return
        val cleanVideoId = videoId?.trim()?.takeIf { it.isNotEmpty() } ?: return

        viewModelScope.launch {
            videoRepository.incrementViews(cleanVideoId)
                .onSuccess { increment ->
                    if (increment.recorded) {
                        _forYouVideos.value = incrementViewInList(_forYouVideos.value, cleanVideoId, increment.totalViews)
                        _followingVideos.value = incrementViewInList(_followingVideos.value, cleanVideoId, increment.totalViews)
                        _searchResults.value = incrementViewInList(_searchResults.value, cleanVideoId, increment.totalViews)
                        _profileVideos.value = incrementViewInList(_profileVideos.value, cleanVideoId, increment.totalViews)
                        // incrementViews() already updates the local counter. Do not
                        // fetch the whole video again here: replacing the item with
                        // a freshly signed media URL makes VideoFeedScreen rebuild
                        // its playlist and seek the visible item back to 0 exactly
                        // when the first view is recorded (~500 ms after playback).
                    }
                }
                .onFailure { error ->
                    playbackViewTracker.allowRetry(cleanVideoId)
                    android.util.Log.e(
                        "VideoFeedVM",
                        "Failed to increment video views for $cleanVideoId",
                        error
                    )
                }
        }
    }

    fun toggleLike(video: Video) {
        val videoId = video.id
        val snapshotForYou = _forYouVideos.value
        val snapshotFollowing = _followingVideos.value
        val snapshotSearch = _searchResults.value
        val snapshotProfile = _profileVideos.value

        val current = findVideoById(videoId) ?: video
        val newLikedState = !current.likedByMe

        _forYouVideos.value = applyLikeState(_forYouVideos.value, videoId, newLikedState)
        _followingVideos.value = applyLikeState(_followingVideos.value, videoId, newLikedState)
        _searchResults.value = applyLikeState(_searchResults.value, videoId, newLikedState)
        _profileVideos.value = applyLikeState(_profileVideos.value, videoId, newLikedState)

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
                restoreVideoListsSnapshot(snapshotForYou, snapshotFollowing, snapshotSearch, snapshotProfile)
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
        _currentUserId.value ?: return
        val videoId = video.id
        if (!shareRequestsInFlight.add(videoId)) return

        val snapshotForYou = _forYouVideos.value
        val snapshotFollowing = _followingVideos.value
        val snapshotSearch = _searchResults.value
        val snapshotProfile = _profileVideos.value

        val current = findVideoById(videoId) ?: video
        val newSharedState = !current.sharedByMe
        val delta = if (newSharedState) 1 else -1

        _forYouVideos.value = applyShareState(_forYouVideos.value, videoId, newSharedState)
        _followingVideos.value = applyShareState(_followingVideos.value, videoId, newSharedState)
        _searchResults.value = applyShareState(_searchResults.value, videoId, newSharedState)
        _profileVideos.value = applyShareState(_profileVideos.value, videoId, newSharedState)

        viewModelScope.launch {
            try {
                val countResult = videoRepository.incrementShareCount(videoId, delta)
                if (countResult.isFailure) {
                    restoreVideoListsSnapshot(snapshotForYou, snapshotFollowing, snapshotSearch, snapshotProfile)
                } else {
                    reloadVideo(videoId)
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoFeedVM", "Share toggle failed", e)
                restoreVideoListsSnapshot(snapshotForYou, snapshotFollowing, snapshotSearch, snapshotProfile)
            } finally {
                shareRequestsInFlight.remove(videoId)
            }
        }
    }

    private fun restoreVideoListsSnapshot(
        forYou: List<Video>,
        following: List<Video>,
        search: List<Video>,
        profile: List<Video>
    ) {
        _forYouVideos.value = forYou
        _followingVideos.value = following
        _searchResults.value = search
        _profileVideos.value = profile
    }

    private fun findVideoById(videoId: String): Video? {
        return _forYouVideos.value.firstOrNull { it.id == videoId }
            ?: _followingVideos.value.firstOrNull { it.id == videoId }
            ?: _searchResults.value.firstOrNull { it.id == videoId }
            ?: _profileVideos.value.firstOrNull { it.id == videoId }
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

    private fun incrementViewInList(list: List<Video>, videoId: String, serverTotal: Int?): List<Video> {
        return list.map { item ->
            if (item.id == videoId) item.copy(views = maxOf(item.views + 1, serverTotal ?: 0)) else item
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
        cancelReply()
        clearMentionSuggestions()
        stopAudio()
        voiceMessageManager.reset() // Reset recording/preview on close
    }
    
    private fun fetchComments(videoId: String) {
        commentsJob?.cancel()
        commentsJob = viewModelScope.launch {
            videoRepository.loadVideoCommentsNow(videoId)
                .onSuccess { comments ->
                    _comments.value = comments
                    updateCommentCount(videoId, comments.size)
                }
                .onFailure { error ->
                    android.util.Log.e("VideoFeedVM", "Failed to load comments for $videoId", error)
                }
            videoRepository.getCommentsFlow(videoId).collect { comments ->
                _comments.value = comments
                updateCommentCount(videoId, comments.size)
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
                 .onSuccess { insertedComment ->
                     android.util.Log.d("VideoFeedVM", "Comment added successfully")
                     val merged = mergeComment(_comments.value, insertedComment)
                     _comments.value = merged
                     updateCommentCount(videoId, merged.size)
                     cancelReply() 
                     clearMentionSuggestions()
                     refreshCommentsAfterWrite(videoId)
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
                excludeUserId = _currentUserId.value,
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
        val previous = _comments.value
        val next = removeVideoCommentImmediately(previous, comment.id)
        _comments.value = next
        updateCommentCount(videoId, next.size)
        viewModelScope.launch {
            videoRepository.deleteComment(comment.id)
                .onSuccess {
                    android.util.Log.d("VideoFeedVM", "Comment deleted successfully")
                    refreshCommentsAfterWrite(videoId)
                    reloadVideo(videoId)
                }
                .onFailure { e ->
                    _comments.value = previous
                    updateCommentCount(videoId, previous.size)
                    android.util.Log.e("VideoFeedVM", "Error deleting comment", e)
                }
        }
    }

    fun addStickerComment(videoId: String, stickerUrl: String, label: String) {
        viewModelScope.launch {
            videoRepository.addStickerComment(videoId, stickerUrl, label)
                .onSuccess { insertedComment ->
                    _comments.value = mergeComment(_comments.value, insertedComment)
                    refreshCommentsAfterWrite(videoId)
                    reloadVideo(videoId)
                }
                .onFailure { android.util.Log.e("VideoFeedVM", "Sticker comment failed", it) }
        }
    }

    fun reactToComment(comment: conectate.com.data.model.VideoComment, reaction: String?) {
        val videoId = selectedVideoForComments.value?.id ?: comment.videoId
        reactToComment(videoId, comment, reaction)
    }

    fun reactToComment(videoId: String, comment: conectate.com.data.model.VideoComment, reaction: String?) {
        val previous = _comments.value
        _comments.value = reactToVideoCommentImmediately(previous, comment.id, reaction)
        viewModelScope.launch {
            videoRepository.setCommentReaction(comment.id, reaction)
                .onSuccess { refreshCommentsAfterWrite(videoId) }
                .onFailure {
                    _comments.value = previous
                    android.util.Log.e("VideoFeedVM", "Comment reaction failed", it)
                }
        }
    }

    fun editComment(comment: conectate.com.data.model.VideoComment, content: String) {
        val videoId = selectedVideoForComments.value?.id ?: comment.videoId
        editComment(videoId, comment, content)
    }

    fun editComment(videoId: String, comment: conectate.com.data.model.VideoComment, content: String) {
        val previous = _comments.value
        _comments.value = editVideoCommentImmediately(previous, comment.id, content)
        viewModelScope.launch {
            videoRepository.editComment(comment.id, content)
                .onSuccess { refreshCommentsAfterWrite(videoId) }
                .onFailure {
                    _comments.value = previous
                    android.util.Log.e("VideoFeedVM", "Comment edit failed", it)
                }
        }
    }

    fun reportComment(comment: conectate.com.data.model.VideoComment, reason: String = "reported") {
        viewModelScope.launch {
            videoRepository.reportComment(comment.id, reason)
                .onFailure { android.util.Log.e("VideoFeedVM", "Comment report failed", it) }
        }
    }

    fun toggleFavorite(video: Video) {
        val wasFavorite = video.favoritedByMe
        fun updateFavorite(list: List<Video>): List<Video> = list.map { item ->
            if (item.id == video.id) {
                item.copy(
                    favoritedByMe = !wasFavorite,
                    favoriteCount = maxOf(0, item.favoriteCount + if (wasFavorite) -1 else 1)
                )
            } else {
                item
            }
        }
        _forYouVideos.value = updateFavorite(_forYouVideos.value)
        _followingVideos.value = updateFavorite(_followingVideos.value)
        _searchResults.value = updateFavorite(_searchResults.value)
        _profileVideos.value = updateFavorite(_profileVideos.value)

        viewModelScope.launch {
            videoRepository.setVideoFavorite(video.id, !wasFavorite)
                .onFailure {
                    android.util.Log.e("VideoFeedVM", "Favorite toggle failed", it)
                    reloadVideo(video.id)
                }
        }
    }

    fun deleteVideo(video: Video) {
        viewModelScope.launch {
            videoRepository.deleteVideo(video.id)
                .onSuccess {
                    _forYouVideos.value = _forYouVideos.value.filterNot { it.id == video.id }
                    _followingVideos.value = _followingVideos.value.filterNot { it.id == video.id }
                    _searchResults.value = _searchResults.value.filterNot { it.id == video.id }
                    _profileVideos.value = _profileVideos.value.filterNot { it.id == video.id }
                    conectate.com.presentation.common.AppFeedbackBus.showSuccess("Video eliminado")
                }
                .onFailure {
                    android.util.Log.e("VideoFeedVM", "Video delete failed", it)
                    conectate.com.presentation.common.AppFeedbackBus.showError(
                        "No se pudo eliminar el video. Verifica tu conexión e inténtalo otra vez.",
                    )
                }
        }
    }

    fun loadSoundVideos(video: Video) {
        val soundId = (video.soundId ?: video.id).trim()
        soundVideosRequest?.cancel()
        val requestId = ++soundVideosRequestId
        val generation = accountGeneration
        _soundVideos.value = listOf(video)
        _soundVideosError.value = null
        if (soundId.isBlank()) {
            _isSoundVideosLoading.value = false
            return
        }
        _isSoundVideosLoading.value = true
        soundVideosRequest = viewModelScope.launch {
            try {
                videoRepository.getVideosBySound(soundId)
                    .onSuccess { videos ->
                        if (generation != accountGeneration || requestId != soundVideosRequestId) return@onSuccess
                        _soundVideos.value = (videos + video)
                            .distinctBy { it.id.trim().lowercase() }
                    }
                    .onFailure { error ->
                        if (generation != accountGeneration || requestId != soundVideosRequestId) return@onFailure
                        _soundVideosError.value = "No se pudieron cargar los videos de este sonido."
                        android.util.Log.w("VideoFeedVM", "Sound catalog unavailable", error)
                    }
            } finally {
                if (generation == accountGeneration && requestId == soundVideosRequestId) {
                    _isSoundVideosLoading.value = false
                }
            }
        }
    }

    fun clearSoundVideos() {
        soundVideosRequest?.cancel()
        soundVideosRequest = null
        soundVideosRequestId++
        _soundVideos.value = emptyList()
        _isSoundVideosLoading.value = false
        _soundVideosError.value = null
    }

    private fun reloadVideo(videoId: String) {
        viewModelScope.launch {
            // Wait for DB triggers/functions to update counts
            delay(300) 
            videoRepository.getVideo(videoId).onSuccess { updatedVideo ->
                // Update lists with new video data (including counts)
                fun updateList(list: List<Video>): List<Video> {
                    return list.map { current ->
                        if (current.id != videoId) current
                        else current.copy(
                            views = maxOf(current.views, updatedVideo.views),
                            likeCount = updatedVideo.likeCount.coerceAtLeast(0),
                            commentCount = updatedVideo.commentCount.coerceAtLeast(0),
                            shareCount = updatedVideo.shareCount.coerceAtLeast(0),
                            likedByMe = updatedVideo.likedByMe,
                            sharedByMe = updatedVideo.sharedByMe
                        )
                    }
                }
                _forYouVideos.value = updateList(_forYouVideos.value)
                _followingVideos.value = updateList(_followingVideos.value)
                _searchResults.value = updateList(_searchResults.value)
                _profileVideos.value = updateList(_profileVideos.value)
            }.onFailure {
                android.util.Log.e("VideoFeedVM", "Failed to reload video after comment update")
            }
        }
    }

    suspend fun refreshVideoForPlayback(videoId: String): Video? {
        val generation = accountGeneration
        val updatedVideo = videoRepository.getVideo(videoId).getOrNull() ?: return null
        // Recovery owns the selected source. Publishing a whole feed here
        // races playlist reconciliation and can overwrite newer reactions.
        return updatedVideo.takeIf { generation == accountGeneration }
    }

    fun updateVideoPlaybackSource(videoId: String, videoUrl: String) {
        fun update(videos: List<Video>) = videos.map { current ->
            if (current.id == videoId) current.copy(videoUrl = videoUrl) else current
        }
        _forYouVideos.value = update(_forYouVideos.value)
        _followingVideos.value = update(_followingVideos.value)
        _searchResults.value = update(_searchResults.value)
        _profileVideos.value = update(_profileVideos.value)
    }

    private fun refreshCommentsAfterWrite(videoId: String) {
        viewModelScope.launch {
            delay(250)
            videoRepository.loadVideoCommentsNow(videoId)
                .onSuccess { comments ->
                    _comments.value = comments
                    updateCommentCount(videoId, comments.size)
                }
                .onFailure { error ->
                    android.util.Log.w("VideoFeedVM", "Post-write comments refresh failed for $videoId", error)
                }
        }
    }

    private fun updateCommentCount(videoId: String, count: Int) {
        fun updateCount(list: List<Video>): List<Video> {
            return list.map {
                if (it.id == videoId) it.copy(commentCount = count) else it
            }
        }
        _forYouVideos.value = updateCount(_forYouVideos.value)
        _followingVideos.value = updateCount(_followingVideos.value)
        _searchResults.value = updateCount(_searchResults.value)
        _profileVideos.value = updateCount(_profileVideos.value)
    }

    private fun mergeComment(
        current: List<conectate.com.data.model.VideoComment>,
        comment: conectate.com.data.model.VideoComment
    ): List<conectate.com.data.model.VideoComment> {
        return mergeLatestVideoComments(current, listOf(comment))
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
            viewModelScope.launch {
                val currentState = voiceState.value
                var fileToSend: File? = null
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
                    videoRepository.sendAudioComment(videoId, fileToSend)
                        .onSuccess {
                            reloadVideo(videoId)
                            refreshCommentsAfterWrite(videoId)
                            cancelReply()
                        }
                        .onFailure { error ->
                            android.util.Log.e("VideoFeedVM", "Error sending audio comment", error)
                        }
                } else {
                    voiceMessageManager.reset()
                }
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
            UbuntuMedia.setDataSource(this, url)
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
        val normalizedUserId = userId.trim().lowercase()
        if (normalizedUserId.isBlank() || normalizedUserId == _currentUserId.value) return
        val next = videoFollowState.begin(normalizedUserId)
        if (next === videoFollowState) return
        val generation = accountGeneration
        publishFollowState(next)
        
        viewModelScope.launch {
            try {
                android.util.Log.d("VideoFeedVM", "followUser called for userId: $userId")
                val result = userRepository.followUser(userId)
                
                result.onSuccess {
                    if (generation != accountGeneration) return@onSuccess
                    android.util.Log.d("VideoFeedVM", "Successfully followed user: $userId")
                    publishFollowState(videoFollowState.complete(normalizedUserId, success = true))
                    // Keep the user in followed set
                    // Optionally reload following videos if on that tab
                    if (_selectedTab.value == 1) {
                        loadFollowingVideos()
                    }
                }.onFailure { e ->
                    if (generation != accountGeneration) return@onFailure
                    android.util.Log.e("VideoFeedVM", "Failed to follow user: ${e.message}", e)
                    // Revert optimistic update on failure
                    publishFollowState(videoFollowState.complete(normalizedUserId, success = false))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (generation != accountGeneration) return@launch
                android.util.Log.e("VideoFeedVM", "Exception in followUser", e)
                // Revert optimistic update on exception
                publishFollowState(videoFollowState.complete(normalizedUserId, success = false))
            }
        }
    }

    private fun publishFollowState(state: VideoFollowState) {
        videoFollowState = state
        _followedUsers.value = state.followed
    }

    fun downloadVideo(video: Video) {
        val videoId = video.id.trim().lowercase()
        if (videoId.isBlank() || video.videoUrl.isBlank()) {
            conectate.com.presentation.common.AppFeedbackBus.showError("Este video no se puede descargar.")
            return
        }
        if (_videoDownloadsInProgress.value.contains(videoId)) return
        _videoDownloadsInProgress.value = _videoDownloadsInProgress.value + videoId
        conectate.com.presentation.common.AppFeedbackBus.show("Preparando video con marca de agua…")
        viewModelScope.launch {
            try {
                videoRepository.downloadVideoToGallery(video)
                    .onSuccess {
                        conectate.com.presentation.common.AppFeedbackBus.showSuccess(
                            "Video guardado en Películas/Novvin"
                        )
                    }
                    .onFailure { error ->
                        conectate.com.presentation.common.AppFeedbackBus.showError(
                            error,
                            "No se pudo descargar el video."
                        )
                    }
            } finally {
                _videoDownloadsInProgress.value = _videoDownloadsInProgress.value - videoId
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
