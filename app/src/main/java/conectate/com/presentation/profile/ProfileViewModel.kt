package conectate.com.presentation.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.model.User
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.VideoRepository
import conectate.com.data.model.Video
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import conectate.com.data.model.UpdateProfileRequest
import conectate.com.data.model.Post
import conectate.com.data.repository.FriendRepository
import conectate.com.data.repository.FriendStatus
import io.github.jan.supabase.realtime.*

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
    private val supabase: SupabaseConfig,
    val videoRepository: VideoRepository,
    private val badgeRepository: conectate.com.data.repository.BadgeRepository,
    val postRepository: conectate.com.data.repository.PostRepository,
    val shareRepository: conectate.com.data.repository.ShareRepository,
    private val friendRepository: FriendRepository
) : ViewModel() {

    private val _profileState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val profileState: StateFlow<ProfileUiState> = _profileState.asStateFlow()

    private val _userVideos = MutableStateFlow<List<Video>>(emptyList())
    val userVideos: StateFlow<List<Video>> = _userVideos.asStateFlow()

    private val _userPosts = MutableStateFlow<List<Post>>(emptyList())
    val userPosts: StateFlow<List<Post>> = _userPosts.asStateFlow()

    private val _sharedPosts = MutableStateFlow<List<Post>>(emptyList())
    val sharedPosts: StateFlow<List<Post>> = _sharedPosts.asStateFlow()

    private val _sharedVideos = MutableStateFlow<List<Video>>(emptyList())
    val sharedVideos: StateFlow<List<Video>> = _sharedVideos.asStateFlow()
    private val postReactionRequestsInFlight = mutableSetOf<String>()

    private val _selectedTab = MutableStateFlow(0) // 0: Videos, 1: Posts, 2: Compartidos
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    val currentUserId: String?
        get() = authService.getCurrentUserId()

    private val _isFollowing = MutableStateFlow(false)
    val isFollowing: StateFlow<Boolean> = _isFollowing.asStateFlow()

    private val _friendStatus = MutableStateFlow(FriendStatus.NONE)
    val friendStatus: StateFlow<FriendStatus> = _friendStatus.asStateFlow()
    private var lockFriendStateUntil = 0L

    val isAdmin: Boolean
        get() {
            // 1. Check email first (failsafe)
            val email = authService.getCurrentUserEmail()?.lowercase()?.trim() ?: ""
            val isDesignatedAdmin = email == "gamergamerff017@gmail.com" || 
                                   email == "alvaromendozamarin004@gmail.com" ||
                                   email == "gamergamerff004@gmail.com"
            
            if (isDesignatedAdmin) return true

            // 2. Check database flag
            val state = _profileState.value
            if (state is ProfileUiState.Success) {
                if (state.user.id == authService.getCurrentUserId()) {
                    return state.user.is_admin
                }
            }
            
            return false
        }

    private var realtimeJob: kotlinx.coroutines.Job? = null

    fun loadUserProfile(userId: String?) {
        val idToLoad = userId ?: authService.getCurrentUserId()
        if (idToLoad == null) {
            _profileState.value = ProfileUiState.Error("No user logged in")
            return
        }

        // Cancel previous realtime subscription if any
        realtimeJob?.cancel()

        // Launch these in parallel immediately, don't wait for user profile
        loadUserVideos(idToLoad)
        loadUserPosts(idToLoad)
        loadSharedContent(idToLoad)

        viewModelScope.launch {
            try {
                // If loading for the first time or different user, show loading
                // But if we are just reloading real-time, we might not want to flash loading...
                // Ideally check if we already have this user loaded.
                val currentState = _profileState.value
                val isSameUser = (currentState is ProfileUiState.Success && currentState.user.id == idToLoad)
                if (!isSameUser) {
                    _profileState.value = ProfileUiState.Loading
                }
                
                // 1. Get User Profile
                val result = userRepository.getUserById(idToLoad)
                result.onSuccess { user ->
                    if (user != null) {
                       // Load badges
                       val badgesResult = badgeRepository.getUserBadges(user.id)
                       val userWithBadges = if (badgesResult.isSuccess) {
                           user.copy(badges = badgesResult.getOrThrow())
                       } else {
                           user
                       }

                       _profileState.value = ProfileUiState.Success(userWithBadges)
                       
                       // Start Realtime Subscription
                       subscribeToProfileUpdates(idToLoad)
                       
                       // Check if following (if looking at another user)
                       val currentId = authService.getCurrentUserId()
                       if (currentId != null && currentId != idToLoad) {
                           // Only check if not currently toggling or within the guard period
                           val now = System.currentTimeMillis()
                           if (!_isToggling.value && (now > lockFollowStateUntil)) {
                               checkIfFollowing(idToLoad)
                           }
                           checkFriendStatus(idToLoad)
                       }
                    } else {
                        _profileState.value = ProfileUiState.Error("User not found")
                    }
                }.onFailure { e ->
                    // If user profile fails, we still might have posts/videos loaded, but we need the user object for the header.
                    // Ideally we show error only if critical data missing.
                    _profileState.value = ProfileUiState.Error(e.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _profileState.value = ProfileUiState.Error("Crash prevented: ${e.message}")
            }
        }
    }

    private fun subscribeToProfileUpdates(userId: String) {
        realtimeJob = viewModelScope.launch {
            try {
                android.util.Log.d("ProfileViewModel", "Subscribing to realtime updates for user: $userId")
                val channel = supabase.client.realtime.channel("profile_$userId")
                val changeset = channel.postgresChangeFlow<io.github.jan.supabase.realtime.PostgresAction.Update>(schema = "public") {
                    table = "profiles"
                    filter = "id=eq.$userId"
                }

                supabase.subscribeRealtimeChannel(
                    channel = channel,
                    reason = "profile_$userId"
                )

                changeset.collect {
                    android.util.Log.d("ProfileViewModel", "Realtime update received for user: $userId")
                    reloadProfileSilently(userId)
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Realtime subscription failed", e)
            }
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
            val result = friendRepository.getFriendStatus(targetId)
            
            // Re-check guard
            if (System.currentTimeMillis() < lockFriendStateUntil) return@launch

            result.onSuccess { status ->
                _friendStatus.value = status
            }
        }
    }

    fun sendFriendRequest() {
        val state = _profileState.value
        if (state !is ProfileUiState.Success) return
        val targetId = state.user.id
        
        viewModelScope.launch {
            // Optimistic update
            _friendStatus.value = FriendStatus.PENDING_SENT
            lockFriendStateUntil = System.currentTimeMillis() + 10000 // 10s guard
            
            val result = friendRepository.sendFriendRequest(targetId)
            if (result.isFailure) {
                // Revert
                _friendStatus.value = FriendStatus.NONE
                lockFriendStateUntil = 0L // Release guard
                android.util.Log.e("ProfileViewModel", "Failed to send friend request", result.exceptionOrNull())
            } else {
                 // Success: keep guard and update timestamp to postpone next check
                 lockFriendStateUntil = System.currentTimeMillis() + 10000
            }
        }
    }

    fun cancelFriendRequest() {
        val state = _profileState.value
        if (state !is ProfileUiState.Success) return
        val targetId = state.user.id
        
        viewModelScope.launch {
            // Optimistic update
            _friendStatus.value = FriendStatus.NONE
            // Remove lock/guard or set it? If we cancel, we want NONE to stay.
            lockFriendStateUntil = System.currentTimeMillis() + 10000 
            
            val result = friendRepository.cancelFriendRequest(targetId)
            if (result.isFailure) {
                // Revert
                _friendStatus.value = FriendStatus.PENDING_SENT
                lockFriendStateUntil = 0L
                android.util.Log.e("ProfileViewModel", "Failed to cancel friend request", result.exceptionOrNull())
            }
        }
    }

    fun unfriendUser() {
        val state = _profileState.value
        if (state !is ProfileUiState.Success) return
        val targetId = state.user.id
        
        viewModelScope.launch {
            // Optimistic update
            _friendStatus.value = FriendStatus.NONE
            lockFriendStateUntil = System.currentTimeMillis() + 10000 
            
            val result = friendRepository.unfriendUser(targetId)
            if (result.isFailure) {
                // Revert
                _friendStatus.value = FriendStatus.FRIENDS
                lockFriendStateUntil = 0L
                android.util.Log.e("ProfileViewModel", "Failed to unfriend", result.exceptionOrNull())
            } else {
                 // Update follower counts if friends implies following (optional but good practice)
            }
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
                    // Revert on failure - DISABLED to prevent flickering
                    // If the backend fails (e.g. spurious 409 or network blip), we keep the UI optimistic.
                    android.util.Log.e("ProfileViewModel", "toggleFollow FAILURE (Ignored in UI): ${result.exceptionOrNull()?.message}")
                    
                    // _isFollowing.value = currentFollowing
                    // _profileState.value = ProfileUiState.Success(currentUser) 
                    
                    // Maintain the lock to prevent immediate overwrite by stale server data
                    lockFollowStateUntil = System.currentTimeMillis() + 15000 
                } else {
                     // Success!
                     android.util.Log.d("ProfileViewModel", "toggleFollow SUCCESS")
                     // Set intense guard (15 seconds) to allow server propagation
                     // and avoid being overwritten by periodic checks
                     lockFollowStateUntil = System.currentTimeMillis() + 15000 
                     lastToggleTime = System.currentTimeMillis()
                     
                     // ✨ RELOAD PROFILE TO GET UPDATED COUNTERS
                     // Wait a bit for database triggers to propagate
                     kotlinx.coroutines.delay(300)
                     reloadProfileSilently(targetId)
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "toggleFollow CRASH (Ignored in UI)", e)
                // _isFollowing.value = currentFollowing
                // _profileState.value = ProfileUiState.Success(currentUser) // Revert
                lockFollowStateUntil = System.currentTimeMillis() + 15000 
            } finally {
                // Keep the toggle state true for a tiny bit longer to prevent immediate check re-entry
                kotlinx.coroutines.delay(500)
                _isToggling.value = false
                android.util.Log.d("ProfileViewModel", "toggleFollow FINISHED (isToggling set to false)")
            }
        }
    }

    fun updateProfile(
        name: String,
        bio: String,
        country: String,
        city: String,
        imageUri: Uri?,
        coverImageUri: Uri?,
        status: String? = null,
        interests: List<String> = emptyList(),
        linkedGames: List<String> = emptyList(),
        gameLogoUrls: Map<String, String> = emptyMap(),
        favoriteMusicUrl: String? = null,
        favoriteBook: String? = null,
        favoriteSport: String? = null,
        career: String? = null,
        availabilitySchedule: String? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val userId = authService.getCurrentUserId()
                if (userId == null) {
                    onError("No user logged in")
                    return@launch
                }

                // Check if name changed to update last_name_change
                val currentUser = (_profileState.value as? ProfileUiState.Success)?.user
                
                // Only include name in request if it actually changed to avoid triggering 7-day limit on photo updates
                val nameChanged = currentUser?.full_name != name && currentUser?.display_name != name
                
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
                var coverUrl: String? = currentUser?.cover_url
                
                // Profile Image Upload Logic
                if (imageUri != null) {
                    val uploadResult = userRepository.uploadProfileImage(imageUri)
                    if (uploadResult.isFailure) {
                        onError(uploadResult.exceptionOrNull()?.message ?: "No se pudo subir la foto de perfil")
                        return@launch
                    }
                    avatarUrl = uploadResult.getOrThrow()
                }
                
                // Cover Image Upload Logic
                if (coverImageUri != null) {
                    val uploadResult = userRepository.uploadCoverImage(coverImageUri)
                    if (uploadResult.isFailure) {
                        onError(uploadResult.exceptionOrNull()?.message ?: "No se pudo subir la portada")
                        return@launch
                    }
                    coverUrl = uploadResult.getOrThrow()
                }

                val updateRequest = UpdateProfileRequest(
                    display_name = finalName, // Null if not changed
                    bio = bio,
                    country = country,
                    city = city,
                    status = status?.trim()?.ifBlank { null },
                    interests = interests.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                    linked_games = linkedGames.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
                    game_logo_urls = gameLogoUrls
                        .mapKeys { it.key.trim() }
                        .mapValues { it.value.trim() }
                        .filter { it.key.isNotBlank() && it.value.isNotBlank() },
                    favorite_music_url = favoriteMusicUrl?.trim()?.ifBlank { null },
                    favorite_book = favoriteBook?.trim()?.ifBlank { null },
                    favorite_sport = favoriteSport?.trim()?.ifBlank { null },
                    career = career?.trim()?.ifBlank { null },
                    availability_schedule = availabilitySchedule?.trim()?.ifBlank { null },
                    avatar_url = avatarUrl,
                    cover_url = coverUrl,
                    last_name_change = lastNameChange
                )

                val result = userRepository.updateProfile(userId, updateRequest)

                result.onSuccess {
                    // Optimistic Update: Update local state immediately
                    val updatedUser = currentUser?.copy(
                        display_name = updateRequest.display_name ?: currentUser.display_name,
                        bio = updateRequest.bio,
                        country = updateRequest.country,
                        city = updateRequest.city,
                        status = updateRequest.status,
                        interests = updateRequest.interests ?: currentUser.interests,
                        linked_games = updateRequest.linked_games ?: currentUser.linked_games,
                        game_logo_urls = updateRequest.game_logo_urls ?: currentUser.game_logo_urls,
                        favorite_music_url = updateRequest.favorite_music_url,
                        favorite_book = updateRequest.favorite_book,
                        favorite_sport = updateRequest.favorite_sport,
                        career = updateRequest.career,
                        availability_schedule = updateRequest.availability_schedule,
                        avatar_url = updateRequest.avatar_url,
                        cover_url = updateRequest.cover_url,
                        last_name_change = updateRequest.last_name_change ?: currentUser.last_name_change
                    )
                    
                    if (updatedUser != null) {
                        _profileState.value = ProfileUiState.Success(
                            user = updatedUser
                        )
                    }

                    onSuccess()
                    // Still reload in background to be safe, but UI is already updated
                    loadUserProfile(null) 
                }.onFailure { e ->
                    onError(e.message ?: "Error updating profile")
                }
            } catch (e: Exception) {
                onError(e.message ?: "Error updating profile")
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

    private fun loadUserVideos(userId: String) {
        viewModelScope.launch {
            try {
                videoRepository.getUserVideos(userId).collect { videos ->
                    _userVideos.value = videos
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Error loading videos", e)
            }
        }
    }

    private fun loadUserPosts(userId: String) {
        viewModelScope.launch {
            try {
                postRepository.getUserPosts(userId).collect { posts ->
                    _userPosts.value = posts
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Error loading user posts", e)
            }
        }
    }

    private fun loadSharedContent(userId: String) {
        viewModelScope.launch {
            try {
                // Fetch shared posts for this user
                postRepository.getSharedPosts(userId).collect { posts ->
                    _sharedPosts.value = posts
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Error loading shared posts", e)
            }
        }
        viewModelScope.launch {
            try {
                // Fetch shared videos for this user
                videoRepository.getSharedVideos(userId).collect { videos ->
                    _sharedVideos.value = videos
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Error loading shared videos", e)
            }
        }
    }

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }

    fun togglePostLike(post: Post) {
        val currentReaction = post.myReaction?.uppercase()?.ifBlank { null }
            ?: if (post.likedByMe) "LIKE" else null
        if (currentReaction != null) {
            clearPostReaction(post)
        } else {
            selectPostReaction(post, "LIKE")
        }
    }

    fun selectPostReaction(post: Post, reactionType: String) {
        updatePostReaction(post, reactionType.uppercase().ifBlank { "LIKE" })
    }

    fun clearPostReaction(post: Post) {
        updatePostReaction(post, null)
    }

    private fun updatePostReaction(post: Post, nextReaction: String?) {
        if (!postReactionRequestsInFlight.add(post.id)) return
        viewModelScope.launch {
            val previousReaction = post.myReaction?.uppercase()?.ifBlank { null }
                ?: if (post.likedByMe) "LIKE" else null
            try {
                fun updateList(list: List<Post>): List<Post> {
                    return list.map { 
                        if (it.id == post.id) applyReactionState(it, previousReaction, nextReaction) else it
                    }
                }

                _userPosts.value = updateList(_userPosts.value)
                _sharedPosts.value = updateList(_sharedPosts.value)

                val result = postRepository.setPostReaction(
                    postId = post.id,
                    reactionType = nextReaction,
                    wasReacted = previousReaction != null
                )
                
                if (result.isFailure) {
                    android.util.Log.e("ProfileViewModel", "Like API failed: ${result.exceptionOrNull()?.message}")
                    _userPosts.value = _userPosts.value.map { if (it.id == post.id) post else it }
                    _sharedPosts.value = _sharedPosts.value.map { if (it.id == post.id) post else it }
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Error toggling post like", e)
                _userPosts.value = _userPosts.value.map { if (it.id == post.id) post else it }
                _sharedPosts.value = _sharedPosts.value.map { if (it.id == post.id) post else it }
            } finally {
                postReactionRequestsInFlight.remove(post.id)
            }
        }
    }

    private fun applyReactionState(
        post: Post,
        previousReaction: String?,
        nextReaction: String?
    ): Post {
        if (previousReaction == nextReaction) return post

        val counts = post.reactionCounts.toMutableMap()
        if (counts.isEmpty() && post.likeCount > 0) {
            counts["LIKE"] = post.likeCount
        }

        if (previousReaction != null) {
            val previousCount = maxOf(0, (counts[previousReaction] ?: 1) - 1)
            if (previousCount == 0) counts.remove(previousReaction) else counts[previousReaction] = previousCount
        }

        if (nextReaction != null) {
            counts[nextReaction] = (counts[nextReaction] ?: 0) + 1
        }

        val countDelta = when {
            previousReaction == null && nextReaction != null -> 1
            previousReaction != null && nextReaction == null -> -1
            else -> 0
        }

        return post.copy(
            likedByMe = nextReaction != null,
            myReaction = nextReaction,
            reactionCounts = counts.toMap(),
            likeCount = maxOf(0, post.likeCount + countDelta)
        )
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                // Use the repository to ensure full cleanup (DB + In-memory + FCM)
                authRepository.signOut()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Reload profile data silently in the background to update counters
     * without showing a loading state
     */
    private suspend fun reloadProfileSilently(userId: String) {
        try {
            android.util.Log.d("ProfileViewModel", "reloadProfileSilently: Fetching updated profile for $userId")
            val result = userRepository.getUserById(userId)
            result.onSuccess { user ->
                if (user != null) {
                    // Only update if we're still viewing the same user
                    val currentState = _profileState.value
                    if (currentState is ProfileUiState.Success && currentState.user.id == userId) {
                        // Load badges for silent reload too
                        val badgesResult = badgeRepository.getUserBadges(user.id)
                        val userWithBadges = if (badgesResult.isSuccess) {
                            user.copy(badges = badgesResult.getOrThrow())
                        } else {
                            user
                        }
                        
                        _profileState.value = ProfileUiState.Success(userWithBadges)
                        android.util.Log.d("ProfileViewModel", "reloadProfileSilently: Updated counters and badges")
                    }
                }
            }.onFailure { e ->
                android.util.Log.e("ProfileViewModel", "reloadProfileSilently: Failed to reload", e)
            }
        } catch (e: Exception) {
            android.util.Log.e("ProfileViewModel", "reloadProfileSilently: Exception", e)
        }
    }
    private val _comments = MutableStateFlow<List<conectate.com.data.model.Comment>>(emptyList())
    val comments: StateFlow<List<conectate.com.data.model.Comment>> = _comments.asStateFlow()

    fun loadComments(postId: String) {
        viewModelScope.launch {
            try {
                android.util.Log.d("ProfileViewModel", "loadComments: Fetching for post $postId")
                postRepository.getPostComments(postId).collect {
                    android.util.Log.d("ProfileViewModel", "loadComments: Success! Received ${it.size} comments for $postId")
                    _comments.value = it
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "loadComments: FAILED for $postId", e)
            }
        }
    }

    fun addComment(postId: String, content: String, parentId: String? = null, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            postRepository.addComment(postId, content, parentId)
                .onSuccess { newComment ->
                    onSuccess()
                    
                    // Optimistic update
                    val currentUser = (_profileState.value as? ProfileUiState.Success)?.user
                    val fullComment = newComment.copy(
                        userName = currentUser?.display_name ?: currentUser?.full_name ?: currentUser?.username ?: "Yo",
                        userAvatar = currentUser?.avatar_url,
                        isVerified = currentUser?.is_verified ?: false,
                        isMine = true
                    )
                    
                    _comments.value = _comments.value + listOf(fullComment)
                    
                    // Reload posts to update comment count globally
                    val targetId = currentUser?.id
                    if (targetId != null) loadUserPosts(targetId)
                }
                .onFailure { onError(it.message ?: "Error al comentar") }
        }
    }

    fun deleteComment(commentId: String, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            postRepository.deleteComment(commentId)
                .onSuccess {
                    onSuccess()
                    // Remove from local list
                    _comments.value = _comments.value.filter { it.id != commentId }
                    
                    // Reload posts to update comment count
                    val currentUser = (_profileState.value as? ProfileUiState.Success)?.user
                    val targetId = currentUser?.id
                    if (targetId != null) loadUserPosts(targetId)
                }
                .onFailure { onError(it.message ?: "Error al eliminar") }
        }
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
        viewModelScope.launch {
            try {
                val userId = authService.getCurrentUserId() ?: return@launch
                val wasShared = post.sharedByMe
                
                // Optimistic Update
                fun updateList(list: List<Post>): List<Post> {
                    return list.map { 
                        if (it.id == post.id) it.copy(sharedByMe = !wasShared) else it 
                    }
                }
                _userPosts.value = updateList(_userPosts.value)
                _sharedPosts.value = if (wasShared) _sharedPosts.value.filter { it.id != post.id } else _sharedPosts.value + post.copy(sharedByMe = true)

                val result = if (wasShared) {
                    shareRepository.unshareContent(userId, "post", post.id)
                } else {
                    shareRepository.shareContent(userId, "post", post.id)
                }

                if (result.isFailure) {
                    // Revert on failure
                    _userPosts.value = updateList(_userPosts.value)
                    loadSharedContent(userId) // Reload to be sure
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Error toggling post share", e)
            }
        }
    }

    fun toggleVideoShare(video: Video) {
        viewModelScope.launch {
            try {
                val userId = authService.getCurrentUserId() ?: return@launch
                val wasShared = video.sharedByMe
                
                // Optimistic Update
                fun updateList(list: List<Video>): List<Video> {
                    return list.map { 
                        if (it.id == video.id) it.copy(sharedByMe = !wasShared) else it 
                    }
                }
                _userVideos.value = updateList(_userVideos.value)
                _sharedVideos.value = if (wasShared) _sharedVideos.value.filter { it.id != video.id } else _sharedVideos.value + video.copy(sharedByMe = true)

                val result = if (wasShared) {
                    shareRepository.unshareContent(userId, "video", video.id)
                } else {
                    shareRepository.shareContent(userId, "video", video.id)
                }

                if (result.isFailure) {
                    // Revert on failure
                    _userVideos.value = updateList(_userVideos.value)
                    loadSharedContent(userId)
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Error toggling video share", e)
            }
        }
    }
}
