package conectate.com.presentation.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.core.media.ProfileMediaMutation
import conectate.com.core.media.ProfileMediaSync
import conectate.com.core.media.ProfileMutationClock
import conectate.com.data.supabase.AuthService
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
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import conectate.com.data.model.UpdateProfileRequest
import conectate.com.data.model.Post
import conectate.com.data.repository.FriendRepository
import conectate.com.data.repository.FriendStatus
import conectate.com.data.repository.PostMutationEvent

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

    private val _favoriteVideos = MutableStateFlow<List<Video>>(emptyList())
    val favoriteVideos: StateFlow<List<Video>> = _favoriteVideos.asStateFlow()

    private val _selectedTab = MutableStateFlow(0) // 0: Videos, 1: Posts, 2: Compartidos, 3: Favoritos
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
            val isDesignatedAdmin = email == "gamergamerff004@gmail.com" ||
                                   email == "alvaromendozamarin004@gmail.com" ||
                                   email == "gamergamerff017@gmail.com"
            
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
    private val profileMutationClock = ProfileMutationClock()

    init {
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
                }
            }
        }
    }

    fun loadUserProfile(userId: String?, forceNetwork: Boolean? = null) {
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
        loadFavoriteVideos(idToLoad)
        val readGeneration = profileMutationClock.snapshot()

        viewModelScope.launch {
            try {
                val currentState = _profileState.value
                val isSameUser = (currentState is ProfileUiState.Success && currentState.user.id == idToLoad)
                if (!isSameUser) {
                    _profileState.value = ProfileUiState.Loading
                }

                val shouldForceNetwork = forceNetwork ?: !isSameUser

                val result = userRepository.getUserById(idToLoad, forceNetwork = shouldForceNetwork)
                result.onSuccess { user ->
                    if (!profileMutationClock.isCurrent(readGeneration)) {
                        android.util.Log.d("ProfileViewModel", "Ignoring stale profile read for $idToLoad")
                        return@onSuccess
                    }
                    if (user != null) {
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
                    if (profileMutationClock.isCurrent(readGeneration)) {
                        _profileState.value = ProfileUiState.Error(e.message ?: "Unknown error")
                    }
                }
            } catch (e: Exception) {
                _profileState.value = ProfileUiState.Error("Crash prevented: ${e.message}")
            }
        }
    }

    private fun subscribeToProfileUpdates(userId: String) {
        android.util.Log.d(
            "ProfileViewModel",
            "Profile realtime for $userId is handled by the Ubuntu session; skipping Supabase realtime."
        )
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
        avatarMediaType: String? = null,
        avatarVideoMuted: Boolean? = null,
        avatarDurationMs: Long? = null,
        coverImageUri: Uri?,
        removeAvatar: Boolean = false,
        removeCover: Boolean = false,
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
                    bio = bio,
                    country = country,
                    city = city,
                    avatar_url = avatarUrl.takeIf { imageUri != null },
                    avatar_media_type = resolvedAvatarMediaType,
                    avatar_video_muted = resolvedAvatarVideoMuted,
                    avatar_duration_ms = resolvedAvatarDurationMs,
                    cover_url = coverUrl.takeIf { coverImageUri != null },
                    remove_avatar = removeAvatar,
                    remove_cover = removeCover,
                    last_name_change = lastNameChange
                )

                val result = userRepository.updateProfile(userId, updateRequest)

                result.onSuccess {
                    profileSaved = true
                    profileMutationClock.markCommitted()
                    val optimisticUser = currentUser?.copy(
                        display_name = updateRequest.display_name ?: currentUser.display_name,
                        bio = updateRequest.bio,
                        country = updateRequest.country,
                        city = updateRequest.city,
                        avatar_url = if (updateRequest.remove_avatar) null else updateRequest.avatar_url ?: currentUser.avatar_url,
                        avatar_media_type = if (updateRequest.remove_avatar) null else updateRequest.avatar_media_type ?: currentUser.avatar_media_type,
                        avatar_video_muted = updateRequest.avatar_video_muted ?: currentUser.avatar_video_muted,
                        avatar_duration_ms = if (updateRequest.remove_avatar) null else updateRequest.avatar_duration_ms ?: currentUser.avatar_duration_ms,
                        cover_url = if (updateRequest.remove_cover) null else updateRequest.cover_url ?: currentUser.cover_url,
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
        viewModelScope.launch {
            try {
                // 1. Optimistic Update: Update local lists immediately without reloading
                val wasLiked = post.likedByMe
                val newLikeCount = if (wasLiked) maxOf(0, post.likeCount - 1) else post.likeCount + 1
                
                fun updateList(list: List<Post>): List<Post> {
                    return list.map { 
                        if (it.id == post.id) it.copy(likedByMe = !wasLiked, likeCount = newLikeCount) else it 
                    }
                }

                _userPosts.value = updateList(_userPosts.value)

                // 2. API Call in background
                val result = postRepository.toggleLike(post.id, wasLiked)
                
                if (result.isFailure) {
                    android.util.Log.e("ProfileViewModel", "Like API failed: ${result.exceptionOrNull()?.message}")
                    // Optionally revert here if stricter consistency is needed, 
                    // but for likes, eventual consistency or user retry is usually acceptable.
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Error toggling post like", e)
            }
        }
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
                    updatePostInLists(postId) { post ->
                        post.copy(commentCount = post.commentCount + 1)
                    }
                    
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
                    val targetPostId = _comments.value.firstOrNull { it.id == commentId }?.postId
                    val removedCount = _comments.value.count { it.id == commentId || it.parentId == commentId }
                    _comments.value = _comments.value.filterNot { it.id == commentId || it.parentId == commentId }
                    if (targetPostId != null) {
                        updatePostInLists(targetPostId) { post ->
                            post.copy(commentCount = maxOf(0, post.commentCount - removedCount.coerceAtLeast(1)))
                        }
                    }
                    
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
                val newShareCount = if (wasShared) maxOf(0, post.shareCount - 1) else post.shareCount + 1
                
                // Optimistic Update
                fun updateList(list: List<Post>): List<Post> {
                    return list.map { 
                        if (it.id == post.id) {
                            it.copy(sharedByMe = !wasShared, shareCount = newShareCount)
                        } else {
                            it
                        }
                    }
                }
                _userPosts.value = updateList(_userPosts.value)
                _sharedPosts.value = if (wasShared) {
                    _sharedPosts.value.filter { it.id != post.id }
                } else {
                    _sharedPosts.value + post.copy(sharedByMe = true, shareCount = newShareCount)
                }

                val result = if (wasShared) {
                    shareRepository.unshareContent(userId, "post", post.id)
                } else {
                    shareRepository.shareContent(userId, "post", post.id)
                }

                if (result.isFailure) {
                    // Revert on failure
                    updatePostInLists(post.id) {
                        it.copy(sharedByMe = wasShared, shareCount = post.shareCount)
                    }
                    if (!wasShared) {
                        _sharedPosts.value = _sharedPosts.value.filterNot { it.id == post.id }
                    }
                    loadSharedContent(userId) // Reload to be sure
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileViewModel", "Error toggling post share", e)
            }
        }
    }

    private fun loadFavoriteVideos(userId: String) {
        viewModelScope.launch {
            try {
                videoRepository.getFavoriteVideos(userId).collect { videos ->
                    _favoriteVideos.value = videos
                }
            } catch (e: Exception) {
                _favoriteVideos.value = emptyList()
                android.util.Log.d("ProfileViewModel", "Favorite videos are unavailable for this profile: ${e.message}")
            }
        }
    }

    fun reportPost(
        post: Post,
        reason: String,
        details: String?,
        evidenceUri: Uri?,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            postRepository.submitContentReport(
                contentType = "post",
                contentId = post.id,
                reportedUserId = post.userId,
                reportedUserName = post.userName,
                reason = reason,
                details = details,
                evidenceUri = evidenceUri
            ).onSuccess {
                onSuccess()
            }.onFailure {
                onError(it.message ?: "No se pudo enviar el reporte")
            }
        }
    }

    fun reportComment(
        comment: conectate.com.data.model.Comment,
        reason: String,
        details: String?,
        evidenceUri: Uri?,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            postRepository.submitContentReport(
                contentType = "comment",
                contentId = comment.id,
                reportedUserId = comment.userId,
                reportedUserName = comment.userName ?: "Usuario",
                reason = reason,
                details = details,
                evidenceUri = evidenceUri
            ).onSuccess {
                onSuccess()
            }.onFailure {
                onError(it.message ?: "No se pudo enviar el reporte")
            }
        }
    }

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
