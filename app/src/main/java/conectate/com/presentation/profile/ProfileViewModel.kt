package conectate.com.presentation.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import conectate.com.core.observability.AppMetrics
import androidx.lifecycle.viewModelScope
import conectate.com.core.media.ProfileMediaMutation
import conectate.com.core.media.ProfileMediaSync
import conectate.com.core.media.ProfileMutationClock
import conectate.com.data.supabase.AuthService
import conectate.com.data.model.User
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.ProfileViewer
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.VideoRepository
import conectate.com.data.model.Video
import conectate.com.data.model.MomentHighlight
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import conectate.com.data.model.UpdateProfileRequest
import conectate.com.data.model.Post
import conectate.com.data.repository.FriendRepository
import conectate.com.data.repository.FriendStatus
import conectate.com.data.repository.PostMutationEvent
import conectate.com.data.repository.MomentHighlightRepository
import conectate.com.presentation.components.PreparedAudioClip
import conectate.com.presentation.posts.PostReactionUsersLoader
import conectate.com.presentation.posts.PostReactionWriter

sealed class ProfileUiState {
    object Loading : ProfileUiState()
    data class Success(val user: User) : ProfileUiState()
    data class Error(val message: String) : ProfileUiState()
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    val userRepository: UserRepository,
    private val authRepository: AuthRepository,
    private val authService: AuthService,
    val videoRepository: VideoRepository,
    private val badgeRepository: conectate.com.data.repository.BadgeRepository,
    val postRepository: conectate.com.data.repository.PostRepository,
    val shareRepository: conectate.com.data.repository.ShareRepository,
    private val friendRepository: FriendRepository,
    private val momentHighlightRepository: MomentHighlightRepository,
    private val momentRepository: conectate.com.data.repository.MomentRepository,
    private val realtime: conectate.com.data.localfallback.UbuntuRealtimeClient,
    private val appMetrics: AppMetrics
) : ViewModel() {

    private val reactionLoader = PostReactionUsersLoader(viewModelScope,
        authService::getCurrentUserId, postRepository::getPostReactions)
    internal val reactionUsersState = reactionLoader.state
    fun loadPostReactions(postId: String, type: String? = null) = reactionLoader.load(postId, type)
    fun clearPostReactions() = reactionLoader.clear()
    private val reactionWriter = PostReactionWriter(viewModelScope, authService::getCurrentUserId,
        { id -> (_userPosts.value + _sharedPosts.value).firstOrNull { it.id == id } },
        postRepository::setPostReaction, postRepository::getPostById,
        { post -> updatePostInLists(post.id) { current -> current.copy(likedByMe = post.likedByMe,
            myReaction = post.myReaction, likeCount = post.likeCount, reactionCounts = post.reactionCounts) } },
        { conectate.com.presentation.common.AppFeedbackBus.show("No se pudo guardar la reacción. Reintenta.") })

    private val _profileState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val profileState: StateFlow<ProfileUiState> = _profileState.asStateFlow()
    private val _contentLoadError = MutableStateFlow<String?>(null)
    val contentLoadError = _contentLoadError.asStateFlow()
    val visibleMoments = momentRepository.moments

    private val _userVideos = MutableStateFlow<List<Video>>(emptyList())
    val userVideos: StateFlow<List<Video>> = _userVideos.asStateFlow()

    private val _userPosts = MutableStateFlow<List<Post>>(emptyList())
    val userPosts: StateFlow<List<Post>> = _userPosts.asStateFlow()

    private val _sharedPosts = MutableStateFlow<List<Post>>(emptyList())
    val sharedPosts: StateFlow<List<Post>> = _sharedPosts.asStateFlow()

    private val _sharedVideos = MutableStateFlow<List<Video>>(emptyList())
    val sharedVideos: StateFlow<List<Video>> = _sharedVideos.asStateFlow()

    private val _favoriteVideos = MutableStateFlow<List<Video>>(emptyList())
    val favoriteVideos: StateFlow<List<Video>> = _favoriteVideos.asStateFlow()

    private val _momentHighlights = MutableStateFlow<List<MomentHighlight>>(emptyList())
    val momentHighlights: StateFlow<List<MomentHighlight>> = _momentHighlights.asStateFlow()
    private val profileVisitorsLoader = ProfileVisitorsLoader(viewModelScope,
        authService::getCurrentUserId, { userRepository.getRecentProfileViewers() })
    internal val profileVisitorsState = profileVisitorsLoader.state
    private val profileVisitRecorder = ProfileVisitRecorder(
        viewModelScope, authService::getCurrentUserId,
    ) { target -> userRepository.recordProfileView(target, targetAllowsHistory = true) }
    private val momentHighlightCache = mutableMapOf<String, List<MomentHighlight>>()
    private var loadedHighlightUserId: String? = null

    private val _selectedTab = MutableStateFlow(0) // 0: Videos, 1: Posts, 2: Compartidos, 3: Favoritos
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    val currentUserId: String?
        get() = authService.getCurrentUserId()

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private val _friendStatus = MutableStateFlow(FriendStatus.NONE)
    private var friendStatusTargetId: String? = null
    val friendStatus: StateFlow<FriendStatus> = _friendStatus.asStateFlow()
    private var lockFriendStateUntil = 0L

    val isAdmin: Boolean
        get() {
            val state = _profileState.value
            if (state is ProfileUiState.Success) {
                if (state.user.id == authService.getCurrentUserId()) {
                    return state.user.is_admin
                }
            }
            
            return false
        }

    private var realtimeJob: kotlinx.coroutines.Job? = null
    private val profileMutationClock = ProfileMutationClock()
    private val profileReadScope = ProfileReadScope(authService::getCurrentUserId)
    private val friendActionScope = ProfileFriendActionScope(authService::getCurrentUserId)
    private val contentSubscriptions = ProfileContentSubscriptions(viewModelScope, profileReadScope)
    private var contentOwner: Pair<String?, String>? = null
    private val postSharesInFlight = mutableSetOf<String>()
    private val sharedPostsPager = ProfileSharedPostsPager(viewModelScope, profileReadScope,
        { userId, offset ->
            val version = reactionWriter.readVersion()
            postRepository.getSharedPostsPage(userId, offset).map { page ->
                page.copy(items = page.items.map { reactionWriter.reconcile(it, version) })
            }
        },
        { page, reset -> _sharedPosts.value = (if (reset) page else _sharedPosts.value + page).distinctBy { it.id } },
        { _contentLoadError.value = "No se pudieron cargar los compartidos. Reintenta." })
    val sharedPostsLoading = sharedPostsPager.loading
    val hasMoreSharedPosts = sharedPostsPager.hasMore
    fun loadMoreSharedPosts() = sharedPostsPager.load()

    private var profileLoadJob: kotlinx.coroutines.Job? = null
    private var profileBadgeJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            shareRepository.postChanges.collect { change ->
                if (change.actorId != authService.getCurrentUserId()) return@collect
                _userPosts.value = _userPosts.value.map(change::applyTo)
                _sharedPosts.value = _sharedPosts.value.map(change::applyTo)
                val displayed = (_profileState.value as? ProfileUiState.Success)?.user?.id
                if (displayed == change.actorId) {
                    if (!change.shared) _sharedPosts.value = _sharedPosts.value.filterNot { it.id == change.postId }
                    sharedPostsPager.refresh(displayed)
                }
            }
        }

        viewModelScope.launch {
            realtime.events.filter { it.event == "profile_views_changed" }.collect { event ->
                val account = authService.getCurrentUserId() ?: return@collect
                val own = (_profileState.value as? ProfileUiState.Success)?.user ?: return@collect
                if (event.channel.equals("user:$account", true) && own.id.equals(account, true))
                    loadProfileViewers(own.profile_view_history_enabled)
            }
        }
        viewModelScope.launch {
            authService.currentSession.map { authService.getCurrentUserId() }.distinctUntilChanged().collect {
                profileVisitorsLoader.accountChanged()
                reactionLoader.clear()
                reactionWriter.clear()
            }
        }
        viewModelScope.launch {
            userRepository.profileUpdates.collect { updatedUser ->
                val current = _profileState.value as? ProfileUiState.Success ?: return@collect
                if (!current.user.id.equals(updatedUser.id, ignoreCase = true)) return@collect
                profileMutationClock.markCommitted()
                _profileState.value = ProfileUiState.Success(
                    updatedUser.copy(badges = current.user.badges)
                )
            }
        }
        viewModelScope.launch {
            postRepository.mutationEvents.collect { event ->
                when (event) {
                    is PostMutationEvent.Deleted -> {
                        _userPosts.value = _userPosts.value.filterNot { it.id == event.postId }
                        _sharedPosts.value = _sharedPosts.value.filterNot { it.id == event.postId }
                    }
                    is PostMutationEvent.ContentUpdated -> updatePostInLists(event.postId) { post ->
                        post.copy(content = event.content)
                    }
                    is PostMutationEvent.VisibilityUpdated -> updatePostInLists(event.postId) { post ->
                        post.copy(visibility = event.visibility)
                    }
                    is PostMutationEvent.RemixPermissionUpdated -> updatePostInLists(event.postId) { post ->
                        post.copy(
                            remixAllowed = event.allowed,
                            remixAudience = event.audience
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            postRepository.reactionChanges.collect { change ->
                if (change.actorId == authService.getCurrentUserId()) reactionWriter.acceptConfirmed(change.postId, change.reaction)
            }
        }
    }

    fun loadUserProfile(userId: String?, forceNetwork: Boolean? = null) {
        val idToLoad = (userId ?: authService.getCurrentUserId())?.trim()?.lowercase()
            ?.takeIf { it.isNotBlank() }
        if (idToLoad == null) {
            friendActionScope.invalidate()
            _profileState.value = ProfileUiState.Error("No user logged in")
            return
        }
        val friendViewChanged = friendActionScope.enter(idToLoad)
        // Never expose the previous Success with a new action context while a cache read suspends.
        if (friendViewChanged) _profileState.value = ProfileUiState.Loading
        val owner = authService.getCurrentUserId() to idToLoad
        contentSubscriptions.cancelAll()
        sharedPostsPager.clear()
        _contentLoadError.value = null
        if (contentOwner?.first != owner.first) {
            momentHighlightCache.clear()
            loadedHighlightUserId = null
            _momentHighlights.value = emptyList()
        }
        if (contentOwner != owner) {
            contentOwner = owner
            _userVideos.value = emptyList(); _userPosts.value = emptyList()
            _sharedPosts.value = emptyList(); _sharedVideos.value = emptyList(); _favoriteVideos.value = emptyList()
        }
        val ticket = profileReadScope.begin(idToLoad)
        profileLoadJob?.cancel()
        profileBadgeJob?.cancel()

        // Friendship changes when a first message creates a request, even if
        // the profile itself is already cached when returning from the chat.
        if (friendViewChanged || friendStatusTargetId != idToLoad) {
            friendStatusTargetId = idToLoad
            _friendStatus.value = FriendStatus.NONE
            lockFriendStateUntil = 0L
        }
        if (idToLoad != authService.getCurrentUserId()) checkFriendStatus(idToLoad)

        // Cancel previous realtime subscription if any
        realtimeJob?.cancel()

        // Launch these in parallel immediately, don't wait for user profile
        loadUserVideos(idToLoad)
        loadUserPosts(idToLoad)
        loadSharedContent(idToLoad)
        loadFavoriteVideos(idToLoad)
        loadMomentHighlights(idToLoad)
        viewModelScope.launch { momentRepository.refreshMomentsForAuthors(listOf(idToLoad)) }
        val readGeneration = profileMutationClock.snapshot()

        profileLoadJob = viewModelScope.launch {
            try {
                val currentState = _profileState.value
                val isSameUser = (currentState is ProfileUiState.Success && currentState.user.id == idToLoad)
                val cachedUser = if (isSameUser) {
                    (currentState as ProfileUiState.Success).user
                } else {
                    userRepository.getCachedUserById(idToLoad)
                }
                if (!profileReadScope.isCurrent(ticket)) return@launch
                if (!isSameUser && cachedUser != null) {
                    _profileState.value = ProfileUiState.Success(cachedUser)
                    profileVisitRecorder.displayed(cachedUser.id, cachedUser.profile_view_history_enabled)
                } else if (!isSameUser) {
                    _profileState.value = ProfileUiState.Loading
                }

                val shouldForceNetwork = forceNetwork ?: !isSameUser
                if (!shouldForceNetwork && cachedUser != null) {
                    profileVisitRecorder.displayed(cachedUser.id, cachedUser.profile_view_history_enabled)
                    if (currentUserId == cachedUser.id) loadProfileViewers(cachedUser.profile_view_history_enabled)
                    refreshProfileBadges(ticket)
                    subscribeToProfileUpdates(idToLoad)
                    return@launch
                }

                val result = withTimeoutOrNull(PROFILE_LOAD_TIMEOUT_MS) {
                    userRepository.getUserById(idToLoad, forceNetwork = shouldForceNetwork)
                }
                if (!profileReadScope.isCurrent(ticket)) return@launch
                if (result == null) {
                    val hasUsableProfile = (_profileState.value as? ProfileUiState.Success)
                        ?.user?.id == idToLoad
                    if (profileMutationClock.isCurrent(readGeneration) && !hasUsableProfile) {
                        _profileState.value = ProfileUiState.Error(
                            "El perfil tardó demasiado en responder. Comprueba tu conexión y reintenta."
                        )
                    }
                    return@launch
                }
                result.onSuccess { user ->
                    if (!profileReadScope.isCurrent(ticket) || !profileMutationClock.isCurrent(readGeneration)) {
                        android.util.Log.d("ProfileViewModel", "Ignoring stale profile read for $idToLoad")
                        return@onSuccess
                    }
                    if (user != null) {
                       profileVisitRecorder.displayed(user.id, user.profile_view_history_enabled)
                       // Identity and music are usable without waiting for optional badges.
                       _profileState.value = ProfileUiState.Success(user)
                       refreshProfileBadges(ticket)

                       if (currentUserId == user.id) {
                           loadProfileViewers(user.profile_view_history_enabled)
                       }
                       
                       // Start Realtime Subscription
                       subscribeToProfileUpdates(idToLoad)
                       
                       // Check if following (if looking at another user)
                       val currentId = authService.getCurrentUserId()
                       if (currentId != null && currentId != idToLoad) {
                           val now = System.currentTimeMillis()
                           if (!_isToggling.value && (now > lockFollowStateUntil)) {
                               checkIfFollowing(idToLoad)
                           }
                       }
                    } else {
                        val hasUsableProfile = (_profileState.value as? ProfileUiState.Success)
                            ?.user?.id == idToLoad
                        if (!hasUsableProfile) _profileState.value = ProfileUiState.Error("User not found")
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    val hasUsableProfile = (_profileState.value as? ProfileUiState.Success)
                        ?.user?.id == idToLoad
                    if (profileMutationClock.isCurrent(readGeneration) && !hasUsableProfile) {
                        _profileState.value = ProfileUiState.Error(e.message ?: "Unknown error")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val hasUsableProfile = (_profileState.value as? ProfileUiState.Success)
                    ?.user?.id == idToLoad
                if (profileReadScope.isCurrent(ticket) && !hasUsableProfile) {
                    _profileState.value = ProfileUiState.Error("No se pudo cargar el perfil. Inténtalo de nuevo.")
                }
            }
        }
    }

    private fun refreshProfileBadges(ticket: ProfileReadScope.Ticket) {
        profileBadgeJob?.cancel()
        profileBadgeJob = viewModelScope.launch {
            try {
                val badges = withTimeoutOrNull(3_000) {
                    badgeRepository.getUserBadges(ticket.target).getOrNull()
                } ?: return@launch
                if (!profileReadScope.isCurrent(ticket)) return@launch
                val latest = (_profileState.value as? ProfileUiState.Success)?.user
                    ?.takeIf { it.id == ticket.target } ?: return@launch
                _profileState.value = ProfileUiState.Success(latest.copy(badges = badges))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Optional badges do not block the displayed profile. */ }
        }
    }

    fun loadProfileViewers(enabled: Boolean = true) {
        profileVisitorsLoader.refresh(enabled)
    }

    private companion object {
        const val PROFILE_LOAD_TIMEOUT_MS = 15_000L
    }

    private fun loadMomentHighlights(userId: String) {
        val ticket = profileReadScope.snapshotFor(userId) ?: return
        if (loadedHighlightUserId != userId) {
            loadedHighlightUserId = userId
            _momentHighlights.value = momentHighlightCache[userId].orEmpty()
        }
        viewModelScope.launch {
            conectate.com.core.network.runReadWithDeadline(10_000) { momentHighlightRepository.getHighlightsForUser(userId).getOrThrow() }
                .onSuccess { highlights ->
                    if (!profileReadScope.isCurrent(ticket)) return@onSuccess
                    val visible = highlights.filter { it.isPublic || userId == currentUserId }
                    momentHighlightCache[userId] = visible
                    if (loadedHighlightUserId == userId) _momentHighlights.value = visible
                }
                .onFailure { error ->
                    if (!profileReadScope.isCurrent(ticket)) return@onFailure
                    android.util.Log.w("ProfileViewModel", "No se pudieron cargar los momentos destacados", error)
                    if (loadedHighlightUserId == userId && momentHighlightCache[userId] == null) {
                        _momentHighlights.value = emptyList()
                    }
                }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun subscribeToProfileUpdates(userId: String) {
        realtimeJob?.cancel()
        val accountId = authService.getCurrentUserId() ?: return
        val channel = "profile-counts:$userId"
        realtimeJob = viewModelScope.launch {
            val token = authService.ensureValidAccessToken().getOrNull() ?: return@launch
            if (authService.getCurrentUserId() != accountId) return@launch
            realtime.updateAccessToken(token); realtime.subscribe(channel)
            try {
                // The subscription acknowledgement also arrives after reconnect.
                // Catch up events missed while this profile was not displayed,
                // without replacing the retained profile by a loading/zero state.
                realtime.events.filter {
                    it.channel == channel && it.event in setOf("profile_counts_updated", "subscription_ready")
                }.debounce(150).collect {
                    if (authService.getCurrentUserId() == accountId && !_isToggling.value) reloadProfileSilently(userId)
                }
            } finally { realtime.unsubscribe(channel) }
        }
    }

    private val _isToggling = MutableStateFlow(false)
    private var lastToggleTime = 0L
    private var lockFollowStateUntil = 0L // Guard against stale server state
    val isToggling: StateFlow<Boolean> = _isToggling.asStateFlow()
    
    private fun checkIfFollowing(targetId: String) {
        // Skip check if toggle is in progress OR we just toggled (cooldown)
        val now = System.currentTimeMillis()
        if (_isToggling.value || (now - lastToggleTime < 5000)) {
            android.util.Log.d("ProfileViewModel", "checkIfFollowing BLOCKED (toggling: ${_isToggling.value}, cooldown: ${now - lastToggleTime}ms)")
            return
        }
        
        viewModelScope.launch {
            android.util.Log.d("ProfileViewModel", "checkIfFollowing START for $targetId")
            val result = userRepository.checkIsFollowing(targetId)
            
            // Re-check state guard before applying
            val finalNow = System.currentTimeMillis()
            if (!_isToggling.value && (finalNow > lockFollowStateUntil)) {
                val newState = result.getOrDefault(false)
                android.util.Log.d("ProfileViewModel", "checkIfFollowing SUCCESS: $newState")
                _isFollowing.value = newState
            } else {
                android.util.Log.d("ProfileViewModel", "checkIfFollowing IGNORED (State Guard Active for ${lockFollowStateUntil - finalNow}ms more)")
            }
        }
    }

    private fun checkFriendStatus(targetId: String) {
        // Guard against stale server state overwriting optimistic updates
        val now = System.currentTimeMillis()
        if (now < lockFriendStateUntil) {
            android.util.Log.d("ProfileViewModel", "checkFriendStatus BLOCKED by guard")
            return
        }

        viewModelScope.launch {
            val ownerId = authService.getCurrentUserId()
            val result = friendRepository.getFriendStatus(targetId)
            
            // Re-check guard
            if (System.currentTimeMillis() < lockFriendStateUntil) return@launch
            if (authService.getCurrentUserId() != ownerId) return@launch
            if (friendStatusTargetId != targetId) return@launch

            result.onSuccess { status ->
                _friendStatus.value = status
            }
        }
    }

    private fun performFriendAction(
        optimistic: FriendStatus,
        previous: FriendStatus,
        action: suspend () -> Result<Unit>
    ) {
        val ticket = friendActionScope.snapshot() ?: return
        viewModelScope.launch {
            friendActionScope.runCurrentAction(ticket,
                onStart = {
                    _friendStatus.value = optimistic
                    lockFriendStateUntil = System.currentTimeMillis() + 10000
                },
                action = action,
                onResult = { result ->
                    if (result.isFailure) {
                        _friendStatus.value = previous
                        lockFriendStateUntil = 0L
                        android.util.Log.e("ProfileViewModel", "Friendship action failed", result.exceptionOrNull())
                    } else {
                        lockFriendStateUntil = System.currentTimeMillis() + 10000
                    }
                }
            )
        }
    }

    fun sendFriendRequest() {
        val targetId = (_profileState.value as? ProfileUiState.Success)?.user?.id ?: return
        performFriendAction(FriendStatus.PENDING_SENT, FriendStatus.NONE) {
            friendRepository.sendFriendRequest(targetId)
        }
    }

    fun cancelFriendRequest() {
        val targetId = (_profileState.value as? ProfileUiState.Success)?.user?.id ?: return
        performFriendAction(FriendStatus.NONE, FriendStatus.PENDING_SENT) {
            friendRepository.cancelFriendRequest(targetId)
        }
    }

    fun unfriendUser() {
        val targetId = (_profileState.value as? ProfileUiState.Success)?.user?.id ?: return
        performFriendAction(FriendStatus.NONE, FriendStatus.FRIENDS) {
            friendRepository.unfriendUser(targetId)
        }
    }

    fun toggleFollow() {
        val state = _profileState.value
        if (state !is ProfileUiState.Success) return
        if (_isToggling.value) return 
        
        val targetId = state.user.id
        val currentFollowing = _isFollowing.value
        
        // Mark as toggling
        _isToggling.value = true
        lastToggleTime = System.currentTimeMillis()
        
        // Optimistic Update
        _isFollowing.value = !currentFollowing
        
        // Optimistic Count Update
        val currentUser = state.user
        val currentCount = currentUser.followers_count ?: 0
        val newCount = if (currentFollowing) maxOf(0, currentCount - 1) else currentCount + 1
        
        // Update user state with new count
        _profileState.value = ProfileUiState.Success(currentUser.copy(followers_count = newCount))

        viewModelScope.launch {
            try {
                android.util.Log.d("ProfileViewModel", "toggleFollow API CALL: ${if (currentFollowing) "UNFOLLOW" else "FOLLOW"}")
                // Ensure we use a clean context for the background operation
                val result = if (currentFollowing) {
                     userRepository.unfollowUser(targetId)
                } else {
                     userRepository.followUser(targetId)
                }
                
                if (result.isFailure) {
                    _isFollowing.value = currentFollowing
                    if ((_profileState.value as? ProfileUiState.Success)?.user?.id == targetId) _profileState.value = ProfileUiState.Success(currentUser)
                    lockFollowStateUntil = 0L
                    conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo guardar el seguimiento. Inténtalo de nuevo.")
                } else {
                    lockFollowStateUntil = 0L
                    reloadProfileSilently(targetId)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                _isFollowing.value = currentFollowing
                if ((_profileState.value as? ProfileUiState.Success)?.user?.id == targetId) _profileState.value = ProfileUiState.Success(currentUser)
                lockFollowStateUntil = 0L
                conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo guardar el seguimiento.")
            } finally {
                // Keep the toggle state true for a tiny bit longer to prevent immediate check re-entry
                _isToggling.value = false
                android.util.Log.d("ProfileViewModel", "toggleFollow FINISHED (isToggling set to false)")
            }
        }
    }

    fun saveProfileMusic(
        clip: PreparedAudioClip?,
        removeMusic: Boolean,
        onSuccess: (String?) -> Unit,
        onError: (String) -> Unit,
    ) {
        val currentUser = (_profileState.value as? ProfileUiState.Success)?.user
        if (currentUser == null) {
            onError("No se pudo cargar tu perfil.")
            return
        }
        if (clip == null && !removeMusic) {
            onSuccess(currentUser.favorite_music_url)
            return
        }

        viewModelScope.launch {
            var uploadedUrl: String? = null
            var profileMusicSaved = false
            fun deleteTemporaryClip() {
                if (clip?.uri?.scheme.equals("file", ignoreCase = true)) {
                    clip?.uri?.path?.let { path -> runCatching { java.io.File(path).delete() } }
                }
            }
            try {
                val nextUrl = if (removeMusic) {
                    ""
                } else {
                    val selectedClip = clip ?: error("Selecciona una canción.")
                    userRepository.uploadProfileMusic(
                        uri = selectedClip.uri,
                        contentTypeHint = selectedClip.mimeType,
                    ).getOrElse { error ->
                        onError(error.message ?: "No se pudo subir la canción seleccionada.")
                        return@launch
                    }.also { uploadedUrl = it }
                }

                val update = userRepository.updateProfile(
                    currentUser.id,
                    UpdateProfileRequest(favorite_music_url = nextUrl),
                )
                if (update.isFailure) {
                    uploadedUrl?.let { userRepository.deleteUploadedProfileMusic(it) }
                    onError(
                        update.exceptionOrNull()?.message
                            ?: "No se pudo guardar la música de entrada.",
                    )
                    return@launch
                }

                profileMusicSaved = true
                profileMutationClock.markCommitted()
                val normalizedUrl = nextUrl.takeIf(String::isNotBlank)
                _profileState.value = ProfileUiState.Success(
                    currentUser.copy(favorite_music_url = normalizedUrl),
                )
                val previousUrl = currentUser.favorite_music_url.orEmpty()
                if (previousUrl.isNotBlank() && previousUrl != nextUrl) {
                    userRepository.deleteUploadedProfileMusic(previousUrl)
                }
                onSuccess(normalizedUrl)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                uploadedUrl?.let { userRepository.deleteUploadedProfileMusic(it) }
                onError(error.message ?: "No se pudo guardar la música de entrada.")
            } finally {
                if (profileMusicSaved) deleteTemporaryClip()
            }
        }
    }

    fun updateProfile(
        name: String?,
        bio: String?,
        country: String?,
        city: String?,
        gender: String? = null,
        birthDate: String? = null,
        education: String? = null,
        workplace: String? = null,
        career: String? = null,
        sectionVisibility: Map<String, String>? = null,
        imageUri: Uri?,
        avatarMediaType: String? = null,
        avatarVideoMuted: Boolean? = null,
        avatarDurationMs: Long? = null,
        coverImageUri: Uri?,
        removeAvatar: Boolean = false,
        removeCover: Boolean = false,
        interests: List<String>? = null,
        hobbies: List<String>? = null,
        linkedGames: List<String>? = null,
        socialLinks: Map<String, String>? = null,
        username: String? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val uploadedForCurrentSave = mutableListOf<String>()
            var profileSaved = false
            try {
                val userId = authService.getCurrentUserId()
                if (userId == null) {
                    onError("No user logged in")
                    return@launch
                }

                // Check if name changed to update last_name_change
                val currentUser = (_profileState.value as? ProfileUiState.Success)?.user
                
                // Only include name in request if it actually changed to avoid triggering 7-day limit on photo updates
                val nameChanged = name != null && currentUser?.full_name != name && currentUser?.display_name != name
                
                var lastNameChange: String? = null
                val finalName = if (nameChanged) {
                    if (!canChangeName(currentUser?.last_name_change)) {
                        onError("No puedes cambiar tu nombre más de una vez cada 7 días.")
                        return@launch
                    }
                    lastNameChange = java.time.LocalDateTime.now().toString()
                    name
                } else {
                    null // Don't send name if not changed
                }
                
                var avatarUrl: String? = currentUser?.avatar_url
                var resolvedAvatarMediaType: String? = currentUser?.avatar_media_type
                var resolvedAvatarVideoMuted: Boolean = currentUser?.avatar_video_muted ?: true
                var resolvedAvatarDurationMs: Long? = currentUser?.avatar_duration_ms
                var coverUrl: String? = currentUser?.cover_url

                if (removeAvatar) {
                    avatarUrl = null
                    resolvedAvatarMediaType = null
                    resolvedAvatarDurationMs = null
                    resolvedAvatarVideoMuted = true
                }
                if (removeCover) coverUrl = null
                
                val (avatarUploadResult, coverUploadResult) = coroutineScope {
                    val avatarUpload = imageUri?.let { selectedUri ->
                        async { userRepository.uploadProfileAvatarMedia(selectedUri) }
                    }
                    val coverUpload = coverImageUri?.let { selectedUri ->
                        async { userRepository.uploadCoverImage(selectedUri) }
                    }
                    avatarUpload?.await() to coverUpload?.await()
                }

                avatarUploadResult?.getOrNull()?.url?.let(uploadedForCurrentSave::add)
                coverUploadResult?.getOrNull()?.let(uploadedForCurrentSave::add)

                val uploadError = avatarUploadResult?.exceptionOrNull()
                    ?: coverUploadResult?.exceptionOrNull()
                if (uploadError != null) {
                    if (uploadError is CancellationException) throw uploadError
                    cleanupProfileUploads(uploadedForCurrentSave)
                    onError(uploadError.message ?: "No se pudo subir la imagen seleccionada")
                    return@launch
                }

                if (avatarUploadResult != null) {
                    val uploadResult = avatarUploadResult
                    val uploaded = uploadResult.getOrThrow()
                    avatarUrl = uploaded.url
                    resolvedAvatarMediaType = uploaded.mediaType
                    resolvedAvatarVideoMuted = avatarVideoMuted ?: true
                    resolvedAvatarDurationMs = uploaded.durationMs ?: avatarDurationMs
                } else {
                    avatarVideoMuted?.let { resolvedAvatarVideoMuted = it }
                    avatarMediaType?.let { resolvedAvatarMediaType = it }
                    avatarDurationMs?.let { resolvedAvatarDurationMs = it }
                }
                
                if (coverUploadResult != null) {
                    val uploadResult = coverUploadResult
                    coverUrl = uploadResult.getOrThrow()
                }

                val updateRequest = UpdateProfileRequest(
                    display_name = finalName, // Null if not changed
                    username = username,
                    bio = bio,
                    country = country,
                    city = city,
                    gender = gender,
                    birth_date = birthDate,
                    education = education,
                    workplace = workplace,
                    career = career,
                    profile_section_visibility = sectionVisibility,
                    avatar_url = avatarUrl.takeIf { imageUri != null },
                    avatar_media_type = resolvedAvatarMediaType.takeIf { imageUri != null || avatarMediaType != null },
                    avatar_video_muted = resolvedAvatarVideoMuted.takeIf { imageUri != null || avatarVideoMuted != null },
                    avatar_duration_ms = resolvedAvatarDurationMs.takeIf { imageUri != null || avatarDurationMs != null },
                    cover_url = coverUrl.takeIf { coverImageUri != null },
                    remove_avatar = removeAvatar,
                    remove_cover = removeCover,
                    interests = interests,
                    hobbies = hobbies,
                    linked_games = linkedGames,
                    social_links = socialLinks,
                    last_name_change = lastNameChange
                )

                val result = userRepository.updateProfile(userId, updateRequest)

                result.onSuccess {
                    profileSaved = true
                    profileMutationClock.markCommitted()
                    val optimisticUser = currentUser?.copy(
                        display_name = updateRequest.display_name ?: currentUser.display_name,
                        username = updateRequest.username ?: currentUser.username,
                        bio = updateRequest.bio ?: currentUser.bio,
                        country = updateRequest.country ?: currentUser.country,
                        city = updateRequest.city ?: currentUser.city,
                        gender = updateRequest.gender ?: currentUser.gender,
                        birth_date = updateRequest.birth_date ?: currentUser.birth_date,
                        education = updateRequest.education ?: currentUser.education,
                        workplace = updateRequest.workplace ?: currentUser.workplace,
                        career = updateRequest.career ?: currentUser.career,
                        profile_section_visibility = updateRequest.profile_section_visibility ?: currentUser.profile_section_visibility,
                        avatar_url = if (updateRequest.remove_avatar) null else updateRequest.avatar_url ?: currentUser.avatar_url,
                        avatar_media_type = if (updateRequest.remove_avatar) null else updateRequest.avatar_media_type ?: currentUser.avatar_media_type,
                        avatar_video_muted = updateRequest.avatar_video_muted ?: currentUser.avatar_video_muted,
                        avatar_duration_ms = if (updateRequest.remove_avatar) null else updateRequest.avatar_duration_ms ?: currentUser.avatar_duration_ms,
                        cover_url = if (updateRequest.remove_cover) null else updateRequest.cover_url ?: currentUser.cover_url,
                        interests = updateRequest.interests ?: currentUser.interests,
                        hobbies = updateRequest.hobbies ?: currentUser.hobbies,
                        linked_games = updateRequest.linked_games ?: currentUser.linked_games,
                        social_links = updateRequest.social_links ?: currentUser.social_links,
                        last_name_change = updateRequest.last_name_change ?: currentUser.last_name_change
                    )
                    val confirmedUser = userRepository.getCachedUserSnapshot(userId)
                        ?.copy(badges = currentUser?.badges)
                        ?: optimisticUser

                    if (confirmedUser != null) {
                        _profileState.value = ProfileUiState.Success(
                            user = confirmedUser
                        )
                    }

                    val obsoleteUrls = ProfileMediaSync.replacedUrls(
                        mutation = ProfileMediaMutation(
                            avatarUrl = updateRequest.avatar_url,
                            coverUrl = updateRequest.cover_url,
                            removeAvatar = updateRequest.remove_avatar,
                            removeCover = updateRequest.remove_cover
                        ),
                        previousAvatarUrl = currentUser?.avatar_url,
                        previousCoverUrl = currentUser?.cover_url,
                        confirmedAvatarUrl = confirmedUser?.avatar_url,
                        confirmedCoverUrl = confirmedUser?.cover_url
                    )

                    onSuccess()
                    if (obsoleteUrls.isNotEmpty()) {
                        withContext(NonCancellable) {
                            cleanupProfileUploads(obsoleteUrls)
                        }
                    }
                }.onFailure { e ->
                    cleanupProfileUploads(uploadedForCurrentSave)
                    onError(e.message ?: "Error updating profile")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!profileSaved) cleanupProfileUploads(uploadedForCurrentSave)
                onError(e.message ?: "Error updating profile")
            }
        }
    }

    private suspend fun cleanupProfileUploads(urls: List<String>) {
        urls.distinct().forEach { url ->
            try {
                userRepository.deleteUploadedProfileMedia(url).onFailure { error ->
                    android.util.Log.w("ProfileViewModel", "No se pudo limpiar una subida de perfil", error)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                android.util.Log.w("ProfileViewModel", "No se pudo limpiar una subida de perfil", error)
            }
        }
    }

    private fun canChangeName(lastChangeStr: String?): Boolean {
        if (lastChangeStr == null) return true
        return try {
            val lastChange = java.time.LocalDateTime.parse(lastChangeStr)
            val days = java.time.temporal.ChronoUnit.DAYS.between(lastChange, java.time.LocalDateTime.now())
            days >= 7
        } catch (e: Exception) {
            true // If parse fails, allow
        }
    }

    fun getProfileVideoDurationMs(uri: Uri): Long =
        userRepository.getProfileVideoDurationMs(uri)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun trimProfileAvatarVideo(
        context: android.content.Context,
        inputUri: Uri,
        startMs: Long,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        val safeStart = startMs.coerceAtLeast(0L)
        val safeEnd = safeStart + 2_000L
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                val outputFile = java.io.File(context.cacheDir, "profile_avatar_${System.currentTimeMillis()}.mp4")
                val mediaItem = androidx.media3.common.MediaItem.fromUri(inputUri)
                    .buildUpon()
                    .setClippingConfiguration(
                        androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(safeStart)
                            .setEndPositionMs(safeEnd)
                            .build()
                    )
                    .build()
                val transformer = androidx.media3.transformer.Transformer.Builder(context)
                    .addListener(object : androidx.media3.transformer.Transformer.Listener {
                        override fun onCompleted(
                            composition: androidx.media3.transformer.Composition,
                            result: androidx.media3.transformer.ExportResult
                        ) {
                            onSuccess(Uri.fromFile(outputFile))
                        }

                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            result: androidx.media3.transformer.ExportResult,
                            exception: androidx.media3.transformer.ExportException
                        ) {
                            onError(exception.message ?: "No se pudo recortar el avatar.")
                        }
                    })
                    .build()

                transformer.start(mediaItem, outputFile.absolutePath)
            } catch (e: Exception) {
                onError(e.message ?: "No se pudo recortar el avatar.")
            }
        }
    }

    private fun loadUserVideos(userId: String) = contentSubscriptions.observe("videos", userId,
        { videoRepository.getUserVideos(userId) }, { _userVideos.value = it },
        { _contentLoadError.value = "No se pudieron cargar los videos del perfil. Reintenta." })

    private fun loadUserPosts(userId: String) = contentSubscriptions.observe("posts", userId,
        {
            val version = reactionWriter.readVersion()
            postRepository.getUserPosts(userId).map { posts -> posts.map { reactionWriter.reconcile(it, version) } }
        }, { _userPosts.value = it },
        { _contentLoadError.value = "No se pudieron cargar las publicaciones del perfil. Reintenta." })

    private fun loadSharedContent(userId: String) {
        sharedPostsPager.refresh(userId)
        contentSubscriptions.observe("sharedVideos", userId, { videoRepository.getSharedVideos(userId) },
            { _sharedVideos.value = it }, { _contentLoadError.value = "No se pudieron cargar los videos compartidos. Reintenta." })
    }

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }

    fun togglePostLike(post: Post) = reactionWriter.toggleLike(post.id)

    fun setPostReaction(post: Post, type: String?) = reactionWriter.set(post.id, type)

    fun signOut() {
        viewModelScope.launch {
            try {
                // Use the repository to ensure full cleanup (DB + In-memory + FCM)
                authRepository.signOut()
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Sign-out failed", e)
                appMetrics.recordFailure("auth_sign_out", e)
            }
        }
    }

    /**
     * Reload profile data silently in the background to update counters
     * without showing a loading state
     */
    private suspend fun reloadProfileSilently(userId: String) {
        val ticket = profileReadScope.snapshotFor(userId) ?: return
        val mutation = profileMutationClock.snapshot()
        try {
            android.util.Log.d("ProfileViewModel", "reloadProfileSilently: Fetching updated profile for $userId")
            val result = userRepository.getUserById(userId)
            result.onSuccess { user ->
                if (user != null && profileReadScope.isCurrent(ticket) && profileMutationClock.isCurrent(mutation)) {
                    // Only update if we're still viewing the same user
                    val currentState = _profileState.value
                    if (currentState is ProfileUiState.Success && currentState.user.id.equals(user.id, ignoreCase = true)) {
                        _profileState.value = ProfileUiState.Success(user.copy(badges = currentState.user.badges))
                        refreshProfileBadges(ticket)
                        android.util.Log.d("ProfileViewModel", "reloadProfileSilently: Updated counters and badges")
                    }
                }
            }.onFailure { e ->
                android.util.Log.e("ProfileViewModel", "reloadProfileSilently: Failed to reload", e)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            android.util.Log.e("ProfileViewModel", "reloadProfileSilently: Exception", e)
        }
    }
    fun syncPostCommentCount(postId: String, count: Int) {
        updatePostInLists(postId) { it.copy(commentCount = count.coerceAtLeast(0)) }
    }

    fun deletePost(postId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            postRepository.deletePost(postId)
                .onSuccess {
                    onSuccess()
                    // Reload posts
                    val targetId = (_profileState.value as? ProfileUiState.Success)?.user?.id
                    if (targetId != null) loadUserPosts(targetId)
                }
                .onFailure { onError(it.message ?: "Error al eliminar") }
        }
    }

    fun updatePostVisibility(postId: String, visibility: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            postRepository.updatePostVisibility(postId, visibility)
                .onSuccess {
                    onSuccess()
                    // Reload posts
                    val targetId = (_profileState.value as? ProfileUiState.Success)?.user?.id
                    if (targetId != null) loadUserPosts(targetId)
                }
                .onFailure { onError(it.message ?: "Error al actualizar") }
        }
    }

    fun updatePostContent(postId: String, content: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            postRepository.updatePostContent(postId, content)
                .onSuccess {
                    onSuccess()
                    // Reload posts to reflect changes
                    val targetId = (_profileState.value as? ProfileUiState.Success)?.user?.id
                    if (targetId != null) loadUserPosts(targetId)
                }
                .onFailure { onError(it.message ?: "Error al actualizar") }
        }
    }

    fun togglePostShare(post: Post) {
        val account = authService.getCurrentUserId() ?: return
        if (!postSharesInFlight.add(post.id)) return
        viewModelScope.launch {
            try {
                val result = if (post.sharedByMe) shareRepository.unshareContent(account, "post", post.id)
                    else shareRepository.shareContent(account, "post", post.id)
                if (account != authService.getCurrentUserId()) return@launch
                conectate.com.presentation.common.AppFeedbackBus.show(
                    if (result.isSuccess) { if (post.sharedByMe) "Quitado de tu perfil" else "Añadido a tu perfil" }
                    else "No se pudo guardar el cambio. Vuelve a intentarlo.")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            finally { postSharesInFlight.remove(post.id) }
        }
    }

    private fun loadFavoriteVideos(userId: String) = contentSubscriptions.observe("favorites", userId,
        { videoRepository.getFavoriteVideos(userId) }, { _favoriteVideos.value = it },
        { _favoriteVideos.value = emptyList(); _contentLoadError.value = "Los favoritos no están disponibles para este perfil." })

    suspend fun reportPost(
        post: Post,
        reason: String,
        details: String?,
        evidenceUri: Uri?,
    ): Result<Unit> =
            postRepository.submitContentReport(
                contentType = "post",
                contentId = post.id,
                reportedUserId = post.userId,
                reportedUserName = post.userName,
                reason = reason,
                details = details,
                evidenceUri = evidenceUri
            )

    suspend fun reportComment(
        comment: conectate.com.data.model.Comment,
        reason: String,
        details: String?,
        evidenceUri: Uri?,
    ): Result<Unit> =
            postRepository.submitContentReport(
                contentType = "comment",
                contentId = comment.id,
                reportedUserId = comment.userId,
                reportedUserName = comment.userName ?: "Usuario",
                reason = reason,
                details = details,
                evidenceUri = evidenceUri
            )

    private fun updatePostInLists(postId: String, transform: (Post) -> Post) {
        _userPosts.value = _userPosts.value.map { post ->
            if (post.id == postId) transform(post) else post
        }
        _sharedPosts.value = _sharedPosts.value.map { post ->
            if (post.id == postId) transform(post) else post
        }
    }

    fun toggleVideoShare(video: Video) {
        viewModelScope.launch {
            try {
                val userId = authService.getCurrentUserId() ?: return@launch
                val wasShared = video.sharedByMe
                val delta = if (wasShared) -1 else 1
                val userVideosSnapshot = _userVideos.value
                val sharedVideosSnapshot = _sharedVideos.value
                
                // Optimistic Update
                fun updateList(list: List<Video>): List<Video> {
                    return list.map { 
                        if (it.id == video.id) {
                            val newShareCount = if (wasShared) maxOf(0, it.shareCount - 1) else it.shareCount + 1
                            it.copy(sharedByMe = !wasShared, shareCount = newShareCount)
                        } else it
                    }
                }
                _userVideos.value = updateList(_userVideos.value)
                _sharedVideos.value = if (wasShared) {
                    _sharedVideos.value.filter { it.id != video.id }
                } else {
                    (_sharedVideos.value.filterNot { it.id == video.id } + video.copy(
                        sharedByMe = true,
                        shareCount = video.shareCount + 1
                    ))
                }

                val result = videoRepository.incrementShareCount(video.id, delta)

                if (result.isFailure) {
                    // Revert on failure
                    _userVideos.value = userVideosSnapshot
                    _sharedVideos.value = sharedVideosSnapshot
                } else {
                    loadSharedContent(userId)
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Error toggling video share", e)
            }
        }
    }
}
