package conectate.com.presentation.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.core.feed.FeedRankingEngine
import conectate.com.core.feed.FeedInteractionAction
import conectate.com.core.feed.FeedSignalTracker
import conectate.com.core.observability.AppMetrics
import conectate.com.data.model.Post
import conectate.com.data.model.PostReactionUser
import conectate.com.data.repository.PostRepository
import conectate.com.data.repository.PostMutationEvent
import conectate.com.data.repository.VideoRepository
import conectate.com.data.repository.AlgorithmRepository
import conectate.com.data.recommendations.OpaqueRecommendationCursor
import conectate.com.data.local.prefs.FeedPreferences
import conectate.com.data.local.prefs.SocialFeedMode
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.SavedLibraryResponse
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.presentation.common.toUserFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import java.util.UUID

private const val SOCIAL_AUTHORITATIVE_REQUEST_TIMEOUT_MS = 5_000L
private const val SOCIAL_FEED_UI_WATCHDOG_MS = 12_000L

@HiltViewModel
class SocialViewModel @Inject constructor(
    private val postRepository: PostRepository,
    private val videoRepository: VideoRepository,
    private val feedRankingEngine: FeedRankingEngine,
    private val feedSignalTracker: FeedSignalTracker,
    private val feedPreferences: FeedPreferences,
    private val appMetrics: AppMetrics,
    private val authService: conectate.com.data.supabase.AuthService,
    private val localFallbackApiClient: LocalFallbackApiClient,
    private val supabaseConfig: SupabaseConfig,
    private val ubuntuRealtimeClient: UbuntuRealtimeClient,
    private val shareRepository: conectate.com.data.repository.ShareRepository,
    private val liveRepository: conectate.com.data.repository.LiveRepository,
    private val socializationRepository: conectate.com.data.repository.SocializationRepository,
    private val algorithmRepository: AlgorithmRepository,
    private val feedSeenHistory: conectate.com.core.feed.FeedSeenHistory,
    appSettingsPreferences: conectate.com.data.local.prefs.AppSettingsPreferences,
    // challengeRepository removed (moved to ChallengeViewModel)
    private val friendRepository: conectate.com.data.repository.FriendRepository,
    val userRepository: conectate.com.data.repository.UserRepository,
    val voiceMessageManager: conectate.com.presentation.messages.VoiceMessageManager,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {
    
    init {
        viewModelScope.launch {
            postRepository.mutationEvents.collect { event ->
                _posts.value = when (event) {
                    is PostMutationEvent.Deleted -> _posts.value.filterNot { it.id == event.postId }
                    is PostMutationEvent.ContentUpdated -> _posts.value.map { post ->
                        if (post.id == event.postId) post.copy(content = event.content) else post
                    }
                    is PostMutationEvent.VisibilityUpdated -> _posts.value.map { post ->
                        if (post.id == event.postId) post.copy(visibility = event.visibility) else post
                    }
                    is PostMutationEvent.RemixPermissionUpdated -> _posts.value.map { post ->
                        if (post.id == event.postId) {
                            post.copy(
                                remixAllowed = event.allowed,
                                remixAudience = event.audience,
                                remixRevokedAt = if (event.allowed) null else post.remixRevokedAt
                            )
                        } else {
                            post
                        }
                    }
                }
            }
        }
    }

    private var submissionsJob: kotlinx.coroutines.Job? = null
    private var countdownJob: kotlinx.coroutines.Job? = null

    private val _currentUser = MutableStateFlow<conectate.com.data.model.User?>(null)
    val currentUser: StateFlow<conectate.com.data.model.User?> = _currentUser.asStateFlow()

    private val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = _posts.asStateFlow()
    private val _authorFollows = MutableStateFlow<Map<String, conectate.com.data.model.AuthorFollowState>>(emptyMap())
    val authorFollows = _authorFollows.asStateFlow()
    private val _followBusy = MutableStateFlow<Set<String>>(emptySet())
    val followBusy = _followBusy.asStateFlow()
    private var followsJob: Job? = null

    val feedError: StateFlow<String?> = postRepository.globalFeedError
    val feedMode: StateFlow<SocialFeedMode> = feedPreferences.mode
    val mutedCreatorIds: StateFlow<Set<String>> = feedPreferences.mutedCreatorIds
    val mutedTerms: StateFlow<Set<String>> = feedPreferences.mutedTerms
    val activeStreams = liveRepository.activeStreams
    val showLiveCarousel = appSettingsPreferences.livesInSocialEnabled

    // Start in a loading state so the empty-feed copy never flashes during
    // the first frame before init launches the initial recommendation query.
    private val _isRefreshing = MutableStateFlow(true)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _isUserRefreshing = MutableStateFlow(false)
    val isUserRefreshing: StateFlow<Boolean> = _isUserRefreshing.asStateFlow()

    private val _friends = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val friends: StateFlow<List<conectate.com.data.model.User>> = _friends.asStateFlow()
    private val _recommendedPeople = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val recommendedPeople: StateFlow<List<conectate.com.data.model.User>> = _recommendedPeople.asStateFlow()
    private val _recommendedVideos = MutableStateFlow<List<conectate.com.data.model.Video>>(emptyList())
    val recommendedVideos: StateFlow<List<conectate.com.data.model.Video>> = _recommendedVideos.asStateFlow()
    private val _socializationCards = MutableStateFlow<List<conectate.com.data.model.SocializationCard>>(emptyList())
    val socializationCards: StateFlow<List<conectate.com.data.model.SocializationCard>> = _socializationCards.asStateFlow()
    private val _reactionUsers = MutableStateFlow<List<PostReactionUser>>(emptyList())
    val reactionUsers: StateFlow<List<PostReactionUser>> = _reactionUsers.asStateFlow()
    private val _reactionsLoading = MutableStateFlow(false)
    val reactionsLoading: StateFlow<Boolean> = _reactionsLoading.asStateFlow()
    private val _reactionsError = MutableStateFlow<String?>(null)
    val reactionsError: StateFlow<String?> = _reactionsError.asStateFlow()
    private val _savedLibrary = MutableStateFlow(SavedLibraryResponse())
    val savedLibrary: StateFlow<SavedLibraryResponse> = _savedLibrary.asStateFlow()
    private val _savedLibraryLoading = MutableStateFlow(false)
    val savedLibraryLoading: StateFlow<Boolean> = _savedLibraryLoading.asStateFlow()
    private val _savedLibraryMessage = MutableStateFlow<String?>(null)
    val savedLibraryMessage: StateFlow<String?> = _savedLibraryMessage.asStateFlow()
    private val likeRequestsInFlight = mutableSetOf<String>()
    private val postReactionRefreshesInFlight = mutableSetOf<String>()
    private val shareRequestsInFlight = mutableSetOf<String>()
    private var postsJob: Job? = null
    private var morePostsJob: Job? = null
    private val authorHydrator = SocialAuthorHydrator(viewModelScope) { ids -> userRepository.getUsersByIds(ids) }
    private var postsGeneration: Long = 0L
    private var discoverySessionSeed = UUID.randomUUID().toString()
    private var previousDiscoveryFirstId: String? = null
    private var previousVisitSeenIds: Set<String> = emptySet()
    private var historySessionSeed: String? = null
    private var feedWasBackgrounded = false
    private var automaticEmptyPageReads = 0
    private var supplementalContentJob: Job? = null
    private var activeAccountId: String? = authService.getCurrentUserId()
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
    private var accountGeneration: Long = 0L
    private var authoritativeCursor: OpaqueRecommendationCursor? = null
    private var authoritativeFeedActive = false
    private var authoritativePageRequestInFlight = false
    private var publicFeedNextOffset: Int? = null
    private val consumedRecommendationCursors = mutableSetOf<OpaqueRecommendationCursor>()
    private val _isLoadingMorePosts = MutableStateFlow(false)
    val isLoadingMorePosts: StateFlow<Boolean> = _isLoadingMorePosts.asStateFlow()
    private val _loadMorePostsError = MutableStateFlow<String?>(null)
    val loadMorePostsError: StateFlow<String?> = _loadMorePostsError.asStateFlow()
    private val _feedScrollReset = MutableStateFlow(0L)
    val feedScrollReset: StateFlow<Long> = _feedScrollReset.asStateFlow()
    private val authoritativeExplanations = mutableMapOf<String, List<String>>()
    private var authoritativeRankingVersion: String? = null
    private var authoritativeExplanationsEnabled = false
    private val _authoritativeHasMore = MutableStateFlow(false)
    val authoritativeHasMore: StateFlow<Boolean> = _authoritativeHasMore.asStateFlow()
    private val _algorithmActionMessage = MutableStateFlow<String?>(null)
    val algorithmActionMessage: StateFlow<String?> = _algorithmActionMessage.asStateFlow()

    // Challenge flows removed


    // Processing votes to prevent double-clicks
    // Processing votes logic removed


    val currentUserId: String?
        get() = authService.getCurrentUserId()

    init {
        viewModelScope.launch {
            authService.currentSession
                .map { session -> session?.accessToken.orEmpty().trim() }
                .distinctUntilChanged()
                .collect { token ->
                    ubuntuRealtimeClient.updateAccessToken(token)
                    if (token.isNotBlank() && localFallbackApiClient.isConfigured &&
                        (supabaseConfig.isLocalFallbackPrimary || authService.isLocalFallbackSession())
                    ) {
                        ubuntuRealtimeClient.subscribe("feed:live")
                    }
                }
        }
        viewModelScope.launch {
            ubuntuRealtimeClient.events
                .filter { envelope -> envelope.channel == "feed:live" && envelope.event == "post_reaction" }
                .collect { envelope ->
                    val postId = envelope.payload["postId"]?.jsonPrimitive?.contentOrNull
                        ?: envelope.payload["post_id"]?.jsonPrimitive?.contentOrNull
                        ?: return@collect
                    if (_posts.value.none { it.id == postId } || !postReactionRefreshesInFlight.add(postId)) {
                        return@collect
                    }
                    val generation = accountGeneration
                    try {
                        postRepository.getPostById(postId).onSuccess { fresh ->
                            if (generation != accountGeneration) return@onSuccess
                            _posts.value = _posts.value.map { current ->
                                if (current.id != postId) current else current.copy(
                                    likedByMe = fresh.likedByMe,
                                    myReaction = fresh.myReaction,
                                    reactionCounts = fresh.reactionCounts,
                                    likeCount = fresh.likeCount
                                )
                            }
                        }
                    } finally {
                        postReactionRefreshesInFlight.remove(postId)
                    }
                }
        }
        feedSignalTracker.setAccount(activeAccountId)
        viewModelScope.launch {
            authService.currentSession
                .map { session ->
                    session?.user?.id?.trim()?.lowercase()
                        ?: authService.getCurrentUserId()?.trim()?.lowercase()
                }
                .distinctUntilChanged()
                .collect { accountId ->
                    if (accountId != activeAccountId) handleAccountChanged(accountId)
                }
        }
        viewModelScope.launch {
            _posts.map { posts -> posts.map { it.userId.trim().lowercase() }.distinct().sorted() }
                .distinctUntilChanged().collect { refreshAuthorFollows() }
        }
        viewModelScope.launch { userRepository.followUpdates.collect { refreshAuthorFollows() } }
        loadPosts()
        scheduleSupplementalContent()
    }

    private fun handleAccountChanged(accountId: String?) {
        activeAccountId = accountId
        feedSignalTracker.setAccount(accountId)
        previousVisitSeenIds = emptySet()
        historySessionSeed = null
        automaticEmptyPageReads = 0
        accountGeneration++
        followsJob?.cancel()
        _authorFollows.value = emptyMap()
        _followBusy.value = emptySet()
        postsGeneration++
        postsJob?.cancel()
        morePostsJob?.cancel()
        authorHydrator.cancel()
        supplementalContentJob?.cancel()
        resetAuthoritativeFeedState()
        _currentUser.value = null
        _posts.value = emptyList()
        _friends.value = emptyList()
        _recommendedPeople.value = emptyList()
        _recommendedVideos.value = emptyList()
        _socializationCards.value = emptyList()
        _reactionUsers.value = emptyList()
        _savedLibrary.value = SavedLibraryResponse()
        _isRefreshing.value = true
        _isUserRefreshing.value = false
        likeRequestsInFlight.clear()
        postReactionRefreshesInFlight.clear()
        shareRequestsInFlight.clear()
        if (accountId.isNullOrBlank()) {
            _isRefreshing.value = false
            return
        }
        loadPosts()
        scheduleSupplementalContent()
    }

    private fun scheduleSupplementalContent() {
        supplementalContentJob?.cancel()
        supplementalContentJob = viewModelScope.launch {
            // The primary Social page owns the first network/database slot.
            // Recommendation rails must not compete with its TTFB or make the
            // user stare at a global loading state on a constrained server.
            _isRefreshing.filter { refreshing -> !refreshing }.first()
            loadCurrentUser()
            loadFriends()
            delay(250L)
            loadRecommendedVideos()
            loadSocializationCards()
        }
    }

    fun loadSocializationCards() {
        val generation = accountGeneration
        viewModelScope.launch {
            socializationRepository.loadCards(surface = "social", placement = "after_12", limit = 4)
                .onSuccess { response ->
                    if (generation != accountGeneration) return@onSuccess
                    _socializationCards.value = if (
                        response.enabled &&
                        response.safeguards.consentRequired &&
                        response.safeguards.serverFiltered &&
                        !response.safeguards.automaticContact
                    ) response.items
                        .filter { it.action.type in setOf("OPEN_PROFILE", "OPEN_COMMUNITY", "OPEN_LIVE") }
                        .distinctBy { it.id }
                    else emptyList()
                }
                .onFailure { error ->
                    if (generation != accountGeneration) return@onFailure
                    val expectedDisabled = (error as? conectate.com.data.socialization.SocializationApiException)
                        ?.code in setOf("SOCIALIZATION_DISABLED", "SOCIALIZATION_CARDS_DISABLED")
                    if (!expectedDisabled) android.util.Log.w("SocialViewModel", "Socialization cards unavailable")
                    _socializationCards.value = emptyList()
                }
        }
    }

    fun dismissSocializationCard(cardId: String) {
        _socializationCards.value = _socializationCards.value.filterNot { it.id == cardId }
    }

    private fun loadRecommendedVideos() {
        val generation = accountGeneration
        viewModelScope.launch {
            // The flow can emit the disk cache first. Use its final emission so
            // recommendations also reflect the configured backend catalog.
            runCatching { videoRepository.getVideos(offset = 0, limit = 24, random = true).last() }
                .onSuccess { videos ->
                    if (generation != accountGeneration) return@onSuccess
                    _recommendedVideos.value = videos
                        .filter { it.isPublic && it.id.isNotBlank() && it.videoUrl.isNotBlank() }
                        .distinctBy { it.id }
                        .take(18)
                }
        }
    }

    private fun loadRecommendedPeople() {
        val generation = accountGeneration
        viewModelScope.launch {
            val currentId = authService.getCurrentUserId()?.trim()?.lowercase().orEmpty()
            if (currentId.isBlank()) return@launch
            val primary = userRepository.getUsersByFollowerThreshold(0)
            val result = if (primary.getOrNull().isNullOrEmpty()) {
                userRepository.getAllUsers(limit = 80)
            } else {
                primary
            }
            result
                .onSuccess { candidates ->
                    if (generation != accountGeneration) return@onSuccess
                    val candidateIds = candidates.map { it.id }.filter { it.isNotBlank() }
                    val followedIds = userRepository.getFollowedUserIds(candidateIds).getOrDefault(emptySet())
                    if (generation != accountGeneration) return@onSuccess
                    val friendIds = _friends.value.map { it.id.trim().lowercase() }.toSet()
                    _recommendedPeople.value = candidates
                        .filter { it.id.trim().lowercase() != currentId }
                        .filterNot { it.isSuspendedNow }
                        .filterNot { followedIds.contains(it.id.trim().lowercase()) }
                        .filterNot { friendIds.contains(it.id.trim().lowercase()) }
                        .distinctBy { it.id.trim().lowercase() }
                        .sortedWith(compareByDescending<conectate.com.data.model.User> { it.is_verified }.thenByDescending { it.followers_count ?: 0 })
                        .take(24)
                }
        }
    }

    private fun refreshAuthorFollows() {
        followsJob?.cancel()
        val generation = accountGeneration
        val ids = _posts.value.map { it.userId.trim().lowercase() }
            .filter { it.isNotBlank() && it != activeAccountId }.distinct()
        if (ids.isEmpty() || activeAccountId == null) { _authorFollows.value = emptyMap(); return }
        followsJob = viewModelScope.launch {
            val result = mutableMapOf<String, conectate.com.data.model.AuthorFollowState>()
            for (chunk in ids.chunked(100)) {
                val following = userRepository.getFollowedUserIds(chunk).getOrNull() ?: return@launch
                val followers = userRepository.getFollowingViewerIds(chunk).getOrNull() ?: return@launch
                if (generation != accountGeneration) return@launch
                chunk.forEach { result[it] = conectate.com.data.model.AuthorFollowState.from(it in following, it in followers) }
            }
            if (generation == accountGeneration) _authorFollows.value = result
        }
    }

    fun followAuthor(userId: String) {
        val id = userId.trim().lowercase()
        if (id == activeAccountId || id in _followBusy.value) return
        val generation = accountGeneration
        _followBusy.value += id
        viewModelScope.launch {
            try {
                userRepository.followUser(id).fold(
                    onSuccess = {
                        if (generation == accountGeneration) {
                            _recommendedPeople.value = _recommendedPeople.value.filterNot { it.id.equals(id, true) }
                            refreshAuthorFollows()
                        }
                    },
                    onFailure = {
                        if (generation == accountGeneration)
                            conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo seguir. Inténtalo nuevamente.")
                    },
                )
            } finally { if (generation == accountGeneration) _followBusy.value -= id }
        }
    }

    fun followRecommendedUser(user: conectate.com.data.model.User) = followAuthor(user.id)

    private fun loadCurrentUser() {
        val generation = accountGeneration
        viewModelScope.launch {
            val userId = authService.getCurrentUserId() ?: return@launch
            userRepository.getUserById(userId).onSuccess {
                if (generation != accountGeneration) return@onSuccess
                _currentUser.value = it
                // New interests inform the next explicit refresh, not a
                // second reshuffle after the user has started reading.
            }
        }
    }

    private fun loadFriends() {
        val generation = accountGeneration
        viewModelScope.launch {
            val userId = authService.getCurrentUserId() ?: return@launch
            friendRepository.getFriends(userId).onSuccess {
                if (generation != accountGeneration) return@onSuccess
                _friends.value = it
                loadRecommendedPeople()
            }.onFailure {
                if (generation != accountGeneration) return@onFailure
                // People suggestions do not require the friend rail to be
                // available; the server/follow filters still protect output.
                loadRecommendedPeople()
            }
        }
    }

    fun loadPosts() = loadPosts(forceLegacy = false, refreshSeed = discoverySessionSeed)

    fun onFeedBackgrounded() { feedWasBackgrounded = true }

    fun onFeedResumed() {
        if (!feedWasBackgrounded) return
        feedWasBackgrounded = false
        if (feedMode.value == SocialFeedMode.DISCOVER) beginDiscoverySession(userInitiated = false)
    }

    fun enterDiscovery() {
        feedWasBackgrounded = false
        if (feedMode.value != SocialFeedMode.DISCOVER) {
            selectFeedMode(SocialFeedMode.DISCOVER)
        } else beginDiscoverySession(userInitiated = false)
    }

    private fun beginDiscoverySession(userInitiated: Boolean) {
        previousDiscoveryFirstId = _posts.value.firstOrNull()?.id
        discoverySessionSeed = UUID.randomUUID().toString()

        // History is reloaded asynchronously before the next cached or network page.
        _posts.value = emptyList()
        _feedScrollReset.value++
        loadPosts(forceLegacy = false, refreshSeed = discoverySessionSeed, userInitiated = userInitiated)
    }

    fun refreshDiscovery() {
        beginDiscoverySession(userInitiated = true)
        scheduleSupplementalContent()
    }

    private fun loadPosts(
        forceLegacy: Boolean,
        refreshSeed: String = UUID.randomUUID().toString(),
        userInitiated: Boolean = false,
    ) {
        val generation = ++postsGeneration
        postsJob?.cancel()
        morePostsJob?.cancel()
        authorHydrator.cancel()
        // Set this before launching so the supplemental scheduler cannot see
        // a stale false value and race the primary request during refresh.
        _isRefreshing.value = true
        _isUserRefreshing.value = userInitiated
        postsJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val uiWatchdog = launch {
                delay(SOCIAL_FEED_UI_WATCHDOG_MS)
                if (generation == postsGeneration && _posts.value.isEmpty()) {
                    // A stalled recommendation/fallback request must never
                    // leave the Social screen blank forever. The request keeps
                    // running and can still populate the feed later.
                    _isRefreshing.value = false
                    _isUserRefreshing.value = false
                }
            }
            try {
                val selectedMode = feedMode.value
                if (selectedMode == SocialFeedMode.DISCOVER && historySessionSeed != refreshSeed) {
                    val account = activeAccountId
                    val seen = feedSeenHistory.snapshot(account, "social")
                    if (generation != postsGeneration || account != activeAccountId) return@launch
                    previousVisitSeenIds = seen
                    historySessionSeed = refreshSeed
                    automaticEmptyPageReads = 0
                }
                resetAuthoritativeFeedState()
                // Do not roll an already visible/edited feed back to disk on
                // each refresh. Cache is only the first-paint fallback.
                val cachedPosts = if (_posts.value.isEmpty()) {
                    postRepository.getCachedGlobalPosts(selectedMode.apiValue)
                } else emptyList()
                if (cachedPosts.isNotEmpty() && generation == postsGeneration) {
                    val visibleCachedPosts = prioritizePreviousVisit(applyFeedControls(cachedPosts))
                    if (visibleCachedPosts.isNotEmpty()) {
                        _posts.value = if (selectedMode == SocialFeedMode.DISCOVER) {
                            orderSocialDiscoveryForSession(visibleCachedPosts, authService.getCurrentUserId(), refreshSeed, previousDiscoveryFirstId, previousVisitSeenIds)
                        } else visibleCachedPosts
                        recordFeedLoaded(startedAt, visibleCachedPosts.size, "local-cache", generation)
                    }
                }
                // With no visible snapshot, do not spend five seconds on the
                // personalized endpoint before requesting the public feed.
                // Personalization is still used for refreshes where the user
                // already has content on screen.
                val canWaitForPersonalization = _posts.value.isNotEmpty()
                if (!forceLegacy && selectedMode == SocialFeedMode.DISCOVER && canWaitForPersonalization) {
                    val authoritativePage = withTimeoutOrNull(SOCIAL_AUTHORITATIVE_REQUEST_TIMEOUT_MS) {
                        algorithmRepository
                            .loadSocialRecommendationPage(cursor = null, limit = 60, refreshSeed = refreshSeed)
                            .getOrNull()
                    }
                    if (generation != postsGeneration) return@launch
                    if (authoritativePage != null) {
                        val visiblePosts = prioritizePreviousVisit(applyFeedControls(authoritativePage.items))
                        if (visiblePosts.isNotEmpty() || (authoritativePage.hasMore && authoritativePage.nextCursor != null)) {
                            authoritativeCursor = authoritativePage.nextCursor
                            val continuation = socialDiscoveryContinuation(authoritativePage.hasMore, authoritativeCursor != null)
                            authoritativeFeedActive = continuation.personalized
                            publicFeedNextOffset = continuation.publicOffset
                            _authoritativeHasMore.value = true
                            authoritativeRankingVersion = authoritativePage.rankingVersion
                            authoritativeExplanationsEnabled = authoritativePage.explanationsEnabled
                            authoritativeExplanations.putAll(authoritativePage.explanationsByCandidateId)
                            val ordered = orderSocialDiscoveryForSession(visiblePosts, authService.getCurrentUserId(), refreshSeed, previousDiscoveryFirstId, previousVisitSeenIds)
                            _posts.value = reconcileSocialFeed(_posts.value, ordered, preserveVisibleOrder = true)
                            recordFeedLoaded(startedAt, visiblePosts.size, "authoritative", generation)
                            refreshVisibleAuthors(generation)
                            return@launch
                        }
                        // Recover from an empty personalized first page using
                        // the public feed. Do not expose recommendation rails
                        // as a temporary first screen while posts are loading.
                        resetAuthoritativeFeedState()
                    }
                }
                val publicPage = postRepository.getGlobalPostPage(limit = 60, mode = selectedMode.apiValue)
                    .getOrThrow()
                if (generation != postsGeneration) return@launch
                publicFeedNextOffset = publicPage.nextOffset
                _authoritativeHasMore.value = publicFeedNextOffset != null
                run {
                    val visiblePosts = prioritizePreviousVisit(applyFeedControls(publicPage.items))
                    val ranked = if (selectedMode == SocialFeedMode.DISCOVER) {
                        rankPosts(visiblePosts, refreshSeed)
                    } else {
                        visiblePosts.sortedByDescending { it.createdAt }
                    }
                    // Keep cached content on a failed request, but honor an
                    // authoritative empty result (e.g. removed/private posts).
                    if (ranked.isEmpty() && postRepository.globalFeedError.value != null) return@run
                    _posts.value = reconcileSocialFeed(_posts.value, ranked, preserveVisibleOrder = true)
                    recordFeedLoaded(startedAt, ranked.size, selectedMode.apiValue, generation)
                    refreshVisibleAuthors(generation)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("SocialViewModel", "Error loading posts", e)
                appMetrics.recordFailure("feed_load", e)
            } finally {
                uiWatchdog.cancel()
                if (generation == postsGeneration) {
                    _isRefreshing.value = false
                    _isUserRefreshing.value = false
                    if (_posts.value.isEmpty() && _authoritativeHasMore.value && postRepository.globalFeedError.value == null && automaticEmptyPageReads++ < 8) loadMorePosts()
                }
            }
        }
    }

    fun loadMorePosts() {
        if (!_authoritativeHasMore.value || authoritativePageRequestInFlight || _isRefreshing.value) return
        val cursor = authoritativeCursor
        val offset = publicFeedNextOffset
        if ((authoritativeFeedActive && cursor == null) || (!authoritativeFeedActive && offset == null)) return
        val generation = postsGeneration
        authoritativePageRequestInFlight = true
        _isLoadingMorePosts.value = true
        _loadMorePostsError.value = null
        morePostsJob = viewModelScope.launch {
            try {
                val items = if (authoritativeFeedActive) {
                    val requested = requireNotNull(cursor)
                    val page = algorithmRepository.loadSocialRecommendationPage(cursor = requested, limit = 60).getOrThrow()
                    if (generation != postsGeneration) return@launch
                    consumedRecommendationCursors.add(requested)
                    authoritativeCursor = page.nextCursor?.takeUnless { it in consumedRecommendationCursors }
                    val continuation = socialDiscoveryContinuation(page.hasMore, authoritativeCursor != null)
                    authoritativeFeedActive = continuation.personalized
                    publicFeedNextOffset = continuation.publicOffset
                    _authoritativeHasMore.value = true
                    authoritativeExplanations.putAll(page.explanationsByCandidateId)
                    page.items
                } else {
                    val requested = requireNotNull(offset)
                    val page = postRepository.getGlobalPostPage(requested, 60, feedMode.value.apiValue).getOrThrow()
                    if (generation != postsGeneration) return@launch
                    publicFeedNextOffset = page.nextOffset?.takeIf { it > requested }
                    _authoritativeHasMore.value = publicFeedNextOffset != null
                    page.items
                }
                _posts.value = (_posts.value + prioritizePreviousVisit(applyFeedControls(items))).distinctBy { it.id }
                refreshVisibleAuthors(generation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == postsGeneration) _loadMorePostsError.value =
                    "No se pudieron cargar más publicaciones. Inténtalo de nuevo."
            } finally {
                if (generation == postsGeneration) {
                    authoritativePageRequestInFlight = false
                    _isLoadingMorePosts.value = false
                    if (_posts.value.isEmpty() && _authoritativeHasMore.value && _loadMorePostsError.value == null && automaticEmptyPageReads++ < 8) loadMorePosts()
                }
            }
        }
    }

    private fun prioritizePreviousVisit(items: List<Post>): List<Post> =
        if (feedMode.value != SocialFeedMode.DISCOVER) items
        else conectate.com.core.feed.prioritizeFeedCandidates(items, previousVisitSeenIds) { it.id }

    private fun resetAuthoritativeFeedState() {
        authoritativeFeedActive = false
        authoritativeCursor = null
        authoritativePageRequestInFlight = false
        publicFeedNextOffset = null
        consumedRecommendationCursors.clear()
        _isLoadingMorePosts.value = false
        _loadMorePostsError.value = null
        authoritativeExplanations.clear()
        authoritativeRankingVersion = null
        authoritativeExplanationsEnabled = false
        _authoritativeHasMore.value = false
    }

    private fun recordFeedLoaded(startedAt: Long, count: Int, mode: String, generation: Long) {
        if (generation != postsGeneration) return
        _isRefreshing.value = false
        _isUserRefreshing.value = false
        val attributes = mapOf("count" to count.toString(), "mode" to mode)
        appMetrics.incrementCounter(metric = "feed_load_success", attributes = attributes)
        appMetrics.recordLatency(
            metric = "feed_load",
            durationMs = System.currentTimeMillis() - startedAt,
            attributes = attributes,
        )
    }

    fun refreshLives() {
        viewModelScope.launch { liveRepository.refreshActiveStreams() }
    }

    fun recoverFeedAfterAppUpdate() {
        viewModelScope.launch {
            repeat(3) { attempt ->
                if (authService.ensureValidAccessToken().isSuccess) {
                    loadPosts()
                    loadCurrentUser()
                    refreshLives()
                    return@launch
                }
                kotlinx.coroutines.delay(500L * (attempt + 1))
            }
        }
    }

    private fun rankPosts(posts: List<Post>, refreshSeed: String? = null): List<Post> {
        val friendIds = _friends.value.map { it.id }.toSet()
        val ranked = feedRankingEngine.rankPosts(
            posts = posts,
            currentUserId = authService.getCurrentUserId(),
            friendIds = friendIds,
            signalSnapshot = feedSignalTracker.snapshot(),
            interestTerms = profileInterestTerms()
        )
        return orderSocialDiscoveryForSession(ranked, authService.getCurrentUserId(), refreshSeed ?: discoverySessionSeed, previousDiscoveryFirstId, previousVisitSeenIds)
    }

    private fun profileInterestTerms(): Set<String> {
        val user = _currentUser.value ?: return emptySet()
        return buildSet {
            addAll(user.interests)
            addAll(user.hobbies)
            addAll(user.linked_games)
            user.favorite_sport?.let(::add)
            user.favorite_book?.let(::add)
            user.career?.let(::add)
        }.map { it.trim().lowercase() }
            .filter { it.length >= 2 }
            .toSet()
    }

    fun recordPostImpression(post: Post) {
        if (_posts.value.none { it.id == post.id }) return
        feedSignalTracker.recordInteraction(post, FeedInteractionAction.IMPRESSION)
        if (feedMode.value == SocialFeedMode.DISCOVER && _posts.value.any { it.id == post.id }) {
            feedSeenHistory.record(activeAccountId, "social", post.id)
        }
    }

    fun selectFeedMode(mode: SocialFeedMode) {
        if (feedMode.value == mode) return
        _posts.value = emptyList()
        _feedScrollReset.value++
        feedPreferences.setMode(mode)
        if (mode == SocialFeedMode.DISCOVER) {
            previousDiscoveryFirstId = null
            discoverySessionSeed = UUID.randomUUID().toString()
        }
        loadPosts()
    }

    fun explainPost(post: Post): List<String> {
        if (authoritativeFeedActive) {
            return authoritativeExplanations[post.id]
                ?: if (authoritativeExplanationsEnabled) {
                    listOf("Mi algoritmo seleccionó este contenido en el servidor.")
                } else {
                    listOf(
                        "Mi algoritmo ordenó este contenido en el servidor" +
                            authoritativeRankingVersion?.let { " ($it)" }.orEmpty() +
                            "; las explicaciones detalladas están desactivadas.",
                    )
                }
        }
        return feedRankingEngine.explainPost(
            post = post,
            currentUserId = authService.getCurrentUserId(),
            friendIds = _friends.value.map { it.id }.toSet(),
            signalSnapshot = feedSignalTracker.snapshot(),
            interestTerms = profileInterestTerms(),
        )
    }

    fun hideCreator(post: Post) {
        feedPreferences.muteCreator(post.userId)
        _posts.value = applyFeedControls(_posts.value)
    }

    fun excludePostFromAlgorithm(post: Post) = applyPostAlgorithmRule(
        post = post,
        dimension = "content",
        subjectKey = post.id,
        subjectLabel = "Esta publicación",
        successMessage = "Esta publicación dejará de recomendarse.",
    )

    fun excludeCreatorFromAlgorithm(post: Post) = applyPostAlgorithmRule(
        post = post,
        dimension = "creator",
        subjectKey = post.userId,
        subjectLabel = post.userName.ifBlank { "Este creador" },
        successMessage = "Verás menos contenido de ${post.userName.ifBlank { "este creador" }}.",
    )

    fun excludePostFormatFromAlgorithm(post: Post) {
        val hasMedia = post.mediaUrls.isNotEmpty() || !post.mediaUrlLegacy.isNullOrBlank()
        applyPostAlgorithmRule(
            post = post,
            dimension = "format",
            subjectKey = if (hasMedia) "image-post" else "text-post",
            subjectLabel = if (hasMedia) "Publicaciones con imagen" else "Publicaciones de texto",
            successMessage = "Este tipo de publicación tendrá menos prioridad.",
        )
    }

    fun excludePostTopicFromAlgorithm(post: Post, topic: String) {
        val normalized = topic.trim().lowercase()
        if (normalized.length < 2) {
            _algorithmActionMessage.value = "Escribe un tema válido."
            return
        }
        applyPostAlgorithmRule(
            post = post,
            dimension = "topic",
            subjectKey = normalized,
            subjectLabel = topic.trim(),
            successMessage = "El tema ${topic.trim()} dejará de recomendarse.",
        )
    }

    fun consumeAlgorithmActionMessage() {
        _algorithmActionMessage.value = null
    }

    private fun applyPostAlgorithmRule(
        post: Post,
        dimension: String,
        subjectKey: String,
        subjectLabel: String,
        successMessage: String,
    ) {
        viewModelScope.launch {
            algorithmRepository.applyExplicitPreference(
                surface = "social",
                dimension = dimension,
                subjectKey = subjectKey,
                subjectLabel = subjectLabel,
                score = -100,
            ).onSuccess {
                _posts.value = _posts.value.filterNot { it.id == post.id }
                _algorithmActionMessage.value = successMessage
            }.onFailure {
                _algorithmActionMessage.value =
                    "No se pudo guardar en Mi algoritmo. Revisa tu conexión e inténtalo nuevamente."
            }
        }
    }

    fun muteTopic(term: String) {
        feedPreferences.muteTerm(term)
        _posts.value = applyFeedControls(_posts.value)
    }

    fun unmuteTopic(term: String) {
        feedPreferences.unmuteTerm(term)
        loadPosts()
    }

    fun clearHiddenCreators() {
        feedPreferences.clearMutedCreators()
        loadPosts()
    }

    fun clearSavedLibraryMessage() {
        _savedLibraryMessage.value = null
    }

    fun loadSavedLibrary(query: String = "") {
        viewModelScope.launch {
            _savedLibraryLoading.value = true
            postRepository.getSavedLibrary(query)
                .onSuccess {
                    _savedLibrary.value = it
                    _savedLibraryMessage.value = null
                }
                .onFailure {
                    _savedLibraryMessage.value =
                        it.toUserFacingMessage("No se pudieron cargar tus guardados.")
                }
            _savedLibraryLoading.value = false
        }
    }

    fun createSavedCollection(name: String) {
        val normalized = name.trim()
        if (normalized.isBlank()) return
        viewModelScope.launch {
            _savedLibraryLoading.value = true
            postRepository.createSavedCollection(normalized)
                .onSuccess {
                    _savedLibraryMessage.value = "Colección creada."
                    loadSavedLibrary()
                }
                .onFailure {
                    _savedLibraryMessage.value =
                        it.toUserFacingMessage("No se pudo crear la colección.")
                }
            _savedLibraryLoading.value = false
        }
    }

    fun savePost(
        post: Post,
        collectionId: String,
        tags: String,
        note: String,
        onSaved: () -> Unit = {}
    ) {
        if (collectionId.isBlank()) return
        val normalizedTags = tags.split(',')
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .take(12)
        viewModelScope.launch {
            _savedLibraryLoading.value = true
            postRepository.savePostToCollection(post.id, collectionId, normalizedTags, note.trim())
                .onSuccess {
                    _savedLibraryMessage.value = "Publicación guardada."
                    loadSavedLibrary()
                    onSaved()
                }
                .onFailure {
                    _savedLibraryMessage.value =
                        it.toUserFacingMessage("No se pudo guardar la publicación.")
                }
            _savedLibraryLoading.value = false
        }
    }

    fun removeSavedPost(collectionId: String, postId: String) {
        viewModelScope.launch {
            _savedLibraryLoading.value = true
            postRepository.removeSavedPost(postId, collectionId)
                .onSuccess {
                    _savedLibraryMessage.value = "Se quitó de la colección."
                    loadSavedLibrary()
                }
                .onFailure {
                    _savedLibraryMessage.value =
                        it.toUserFacingMessage("No se pudo quitar el guardado.")
                }
            _savedLibraryLoading.value = false
        }
    }

    private fun applyFeedControls(posts: List<Post>): List<Post> {
        val mutedCreators = feedPreferences.mutedCreatorIds.value
        val mutedWords = feedPreferences.mutedTerms.value
        return posts.filter { post ->
            post.userId.trim().lowercase() !in mutedCreators &&
                mutedWords.none { word -> post.content.contains(word, ignoreCase = true) }
        }
    }

    private fun refreshVisibleAuthors(generation: Long) {
        authorHydrator.refresh(_posts.value) { profiles ->
            if (generation == postsGeneration) {
                _posts.value = applyCanonicalPostAuthors(_posts.value, profiles)
            }
        }
    }

    fun recordPostOpen(post: Post) {
        feedSignalTracker.recordInteraction(post, FeedInteractionAction.OPEN)
    }

    fun recordPostComment(post: Post) {
        feedSignalTracker.recordInteraction(post, FeedInteractionAction.COMMENT)
    }

    fun toggleLike(post: Post) {
        val oldReaction = post.myReaction ?: if (post.likedByMe) "LIKE" else null
        setReaction(post, if (oldReaction == null) "LIKE" else null)
    }

    fun setReaction(post: Post, reactionType: String?) {
        if (!likeRequestsInFlight.add(post.id)) return
        viewModelScope.launch {
            val oldReaction = post.myReaction ?: if (post.likedByMe) "LIKE" else null
            val requestedReaction = reactionType?.trim()?.uppercase()?.takeIf(String::isNotBlank)
            val newReaction = requestedReaction?.takeUnless { it == oldReaction }
            val oldCounts = post.reactionCounts.toMutableMap().also { counts ->
                if (counts.isEmpty() && post.likeCount > 0) counts["LIKE"] = post.likeCount
            }
            try {
                val newCounts = oldCounts.toMutableMap()
                oldReaction?.let { old ->
                    newCounts[old] = ((newCounts[old] ?: 0) - 1).coerceAtLeast(0)
                    if (newCounts[old] == 0) newCounts.remove(old)
                }
                newReaction?.let { selected ->
                    newCounts[selected] = (newCounts[selected] ?: 0) + 1
                }
                val newLikeCount = newCounts.values.sum()
                _posts.value = _posts.value.map {
                    if (it.id == post.id) {
                        it.copy(
                            likedByMe = newReaction != null,
                            likeCount = newLikeCount,
                            myReaction = newReaction,
                            reactionCounts = newCounts
                        )
                    } else {
                        it
                    }
                }
                feedSignalTracker.recordInteraction(
                    post = post,
                    action = if (newReaction == null) FeedInteractionAction.UNLIKE else FeedInteractionAction.LIKE
                )

                val result = postRepository.setPostReaction(
                    postId = post.id,
                    reactionType = newReaction,
                    wasReacted = oldReaction != null
                )
                if (result.isFailure) {
                    _posts.value = _posts.value.map {
                        if (it.id == post.id) post else it
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SocialViewModel", "Error updating reaction", e)
                _posts.value = _posts.value.map {
                    if (it.id == post.id) post else it
                }
            } finally {
                likeRequestsInFlight.remove(post.id)
            }
        }
    }

    fun loadPostReactions(postId: String, reactionType: String? = null) {
        viewModelScope.launch {
            _reactionsLoading.value = true
            _reactionsError.value = null
            postRepository.getPostReactions(postId, reactionType)
                .onSuccess { _reactionUsers.value = it }
                .onFailure {
                    _reactionUsers.value = emptyList()
                    _reactionsError.value = it.toUserFacingMessage("No se pudieron cargar las reacciones.")
                }
            _reactionsLoading.value = false
        }
    }

    fun clearPostReactions() {
        _reactionUsers.value = emptyList()
        _reactionsError.value = null
        _reactionsLoading.value = false
    }

    fun togglePostShare(post: Post) {
        if (!shareRequestsInFlight.add(post.id)) return
        viewModelScope.launch {
            try {
                val userId = authService.getCurrentUserId() ?: return@launch
                val wasShared = post.sharedByMe
                val newShareCount = if (wasShared) maxOf(0, post.shareCount - 1) else post.shareCount + 1
                
                android.util.Log.d("SocialViewModel", "togglePostShare: wasShared=$wasShared, postId=${post.id}")
                
                // Optimistic Update
                _posts.value = _posts.value.map {
                    if (it.id == post.id) it.copy(sharedByMe = !wasShared, shareCount = newShareCount) else it
                }
                feedSignalTracker.recordInteraction(
                    post = post,
                    action = if (wasShared) FeedInteractionAction.UNSHARE else FeedInteractionAction.SHARE
                )

                val result = if (wasShared) {
                    shareRepository.unshareContent(userId, "post", post.id)
                } else {
                    shareRepository.shareContent(userId, "post", post.id)
                }

                if (result.isFailure) {
                    android.util.Log.e("SocialViewModel", "togglePostShare FAILED", result.exceptionOrNull())
                    android.widget.Toast.makeText(
                        context,
                        result.exceptionOrNull().toUserFacingMessage("No se pudo compartir la publicación."),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    // Revert on failure
                    _posts.value = _posts.value.map {
                        if (it.id == post.id) it.copy(sharedByMe = wasShared, shareCount = post.shareCount) else it
                    }
                } else {
                    android.util.Log.d("SocialViewModel", "togglePostShare SUCCESS")
                    val message = if (wasShared) "Eliminado de tu perfil" else "Añadido a tu perfil"
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("SocialViewModel", "Error toggling post share", e)
                _posts.value = _posts.value.map {
                    if (it.id == post.id) it.copy(sharedByMe = post.sharedByMe, shareCount = post.shareCount) else it
                }
                android.widget.Toast.makeText(
                    context,
                    e.toUserFacingMessage("No se pudo compartir la publicación."),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } finally {
                shareRequestsInFlight.remove(post.id)
            }
        }
    }
    fun createPost(
        content: String,
        uris: List<android.net.Uri>,
        visibility: String = "public",
        isSensitive: Boolean = false,
        fromDraft: Boolean = false,
        draftOwnerId: String? = null,
    ) {
        viewModelScope.launch {
            try {
                if (draftOwnerId != null && currentUserId != draftOwnerId) {
                    android.widget.Toast.makeText(context, "La cuenta cambió. Abre tu borrador en la cuenta original.",
                        android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                val result = postRepository.createPost(
                    content = content,
                    mediaUris = uris,
                    visibility = visibility,
                    isSensitive = isSensitive
                )
                if (result.isSuccess) {
                    val created = result.getOrThrow()
                    if (fromDraft && created.userId.equals(draftOwnerId, ignoreCase = true)) {
                        draftOwnerId?.let { account ->
                            conectate.com.data.local.PostDraftStore.delete(context, account)
                                .onFailure { android.util.Log.w("SocialViewModel", "No se pudo limpiar el borrador publicado", it) }
                        }
                    }
                    if (draftOwnerId != null && currentUserId != draftOwnerId) return@launch
                    _posts.value = listOf(created) + _posts.value.filterNot { it.id == created.id }
                    feedSignalTracker.recordInteraction(created, FeedInteractionAction.IMPRESSION)
                    android.widget.Toast.makeText(context, "Publicación creada", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(
                        context,
                        result.exceptionOrNull().toUserFacingMessage("No se pudo publicar."),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("SocialViewModel", "Error creating post", e)
                android.widget.Toast.makeText(context, "Error al publicar", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun reportPost(post: Post, reason: String, details: String?, evidenceUri: android.net.Uri?) {
        viewModelScope.launch {
            val result = postRepository.submitContentReport(
                contentType = "post",
                contentId = post.id,
                reportedUserId = post.userId,
                reportedUserName = post.userName,
                reason = reason,
                details = details,
                evidenceUri = evidenceUri
            )
            if (result.isSuccess) {
                android.widget.Toast.makeText(context, "Reporte enviado", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val message = result.exceptionOrNull().toUserFacingMessage("No se pudo enviar el reporte.")
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun deletePost(postId: String) {
        viewModelScope.launch {
            val result = postRepository.deletePost(postId)
            if (result.isSuccess) {
                _posts.value = _posts.value.filter { it.id != postId }
                android.widget.Toast.makeText(context, "Publicación eliminada", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val message = result.exceptionOrNull().toUserFacingMessage("No se pudo eliminar la publicación.")
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun updatePostVisibility(postId: String, visibility: String) {
        viewModelScope.launch {
            val result = postRepository.updatePostVisibility(postId, visibility)
            if (result.isSuccess) {
                _posts.value = _posts.value.map {
                    if (it.id == postId) it.copy(visibility = visibility) else it
                }
                android.widget.Toast.makeText(context, "Visibilidad actualizada", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val message = result.exceptionOrNull().toUserFacingMessage("No se pudo actualizar la visibilidad.")
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun createRemix(post: Post, content: String) {
        viewModelScope.launch {
            val result = postRepository.createRemix(post.id, content)
            if (result.isSuccess) {
                val created = result.getOrThrow()
                _posts.value = listOf(created) + _posts.value.filterNot { it.id == created.id }
                android.widget.Toast.makeText(context, "Remix publicado con atribución", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val message = result.exceptionOrNull().toUserFacingMessage("No se pudo crear el remix.")
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun updateRemixPermission(post: Post, allowed: Boolean, audience: String = post.remixAudience) {
        viewModelScope.launch {
            val result = postRepository.updateRemixPermission(post.id, allowed, audience)
            if (result.isSuccess) {
                val updated = result.getOrThrow()
                _posts.value = _posts.value.map { item -> if (item.id == updated.id) updated else item }
                val message = if (updated.remixAllowed) "Remixes permitidos" else "Remixes desactivados"
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val message = result.exceptionOrNull().toUserFacingMessage("No se pudo cambiar el permiso de remix.")
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun updatePostContent(postId: String, content: String) {
        viewModelScope.launch {
            val result = postRepository.updatePostContent(postId, content)
            if (result.isSuccess) {
                _posts.value = _posts.value.map {
                    if (it.id == postId) it.copy(content = content) else it
                }
                android.widget.Toast.makeText(context, "Publicación actualizada", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val message = result.exceptionOrNull().toUserFacingMessage("No se pudo actualizar la publicación.")
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Challenge submission methods removed (moved to ChallengeViewModel)

    // loadSubmissions removed


    // toggleChallengeVote removed


    // observeChallenges removed


    // Helpers removed


    // Other methods removed

}
