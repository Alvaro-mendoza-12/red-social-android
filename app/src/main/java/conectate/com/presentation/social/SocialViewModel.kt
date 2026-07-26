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
import conectate.com.data.local.prefs.FeedPreferences
import conectate.com.data.local.prefs.SocialFeedMode
import conectate.com.data.localfallback.SavedLibraryResponse
import conectate.com.presentation.common.toUserFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SocialViewModel @Inject constructor(
    private val postRepository: PostRepository,
    private val feedRankingEngine: FeedRankingEngine,
    private val feedSignalTracker: FeedSignalTracker,
    private val feedPreferences: FeedPreferences,
    private val appMetrics: AppMetrics,
    private val authService: conectate.com.data.supabase.AuthService,
    private val shareRepository: conectate.com.data.repository.ShareRepository,
    private val liveRepository: conectate.com.data.repository.LiveRepository,
    // challengeRepository removed (moved to ChallengeViewModel)
    private val friendRepository: conectate.com.data.repository.FriendRepository,
    val userRepository: conectate.com.data.repository.UserRepository,
    val voiceMessageManager: conectate.com.presentation.messages.VoiceMessageManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {
    
    init {
        voiceMessageManager.setScope(viewModelScope)
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
    val feedMode: StateFlow<SocialFeedMode> = feedPreferences.mode
    val mutedCreatorIds: StateFlow<Set<String>> = feedPreferences.mutedCreatorIds
    val mutedTerms: StateFlow<Set<String>> = feedPreferences.mutedTerms
    val activeStreams = liveRepository.activeStreams

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _friends = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val friends: StateFlow<List<conectate.com.data.model.User>> = _friends.asStateFlow()
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
    private val shareRequestsInFlight = mutableSetOf<String>()
    private var postsJob: Job? = null

    // Challenge flows removed


    // Processing votes to prevent double-clicks
    // Processing votes logic removed


    val currentUserId: String?
        get() = authService.getCurrentUserId()

    init {
        loadPosts()
        loadCurrentUser()
        loadFriends()
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            val userId = authService.getCurrentUserId() ?: return@launch
            userRepository.getUserById(userId).onSuccess {
                _currentUser.value = it
                if (_posts.value.isNotEmpty()) {
                    _posts.value = rankPosts(_posts.value)
                }
            }
        }
    }

    private fun loadFriends() {
        viewModelScope.launch {
            val userId = authService.getCurrentUserId() ?: return@launch
            friendRepository.getFriends(userId).onSuccess {
                _friends.value = it
                if (_posts.value.isNotEmpty()) {
                    _posts.value = rankPosts(_posts.value)
                }
            }
        }
    }

    fun loadPosts() {
        postsJob?.cancel()
        postsJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _isRefreshing.value = true
            try {
                val selectedMode = feedMode.value
                postRepository.getGlobalPosts(
                    limit = 60,
                    mode = selectedMode.apiValue
                ).collect { globalPosts ->
                    val visiblePosts = applyFeedControls(globalPosts)
                    val ranked = if (selectedMode == SocialFeedMode.DISCOVER) {
                        rankPosts(visiblePosts)
                    } else {
                        visiblePosts.sortedByDescending { it.createdAt }
                    }
                    _posts.value = ranked
                    _isRefreshing.value = false
                    appMetrics.incrementCounter(
                        metric = "feed_load_success",
                        attributes = mapOf(
                            "count" to ranked.size.toString(),
                            "mode" to selectedMode.apiValue
                        )
                    )
                    appMetrics.recordLatency(
                        metric = "feed_load",
                        durationMs = System.currentTimeMillis() - startedAt,
                        attributes = mapOf(
                            "count" to ranked.size.toString(),
                            "mode" to selectedMode.apiValue
                        )
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("SocialViewModel", "Error loading posts", e)
                appMetrics.recordFailure("feed_load", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refreshLives() {
        viewModelScope.launch { liveRepository.refreshActiveStreams() }
    }

    private fun rankPosts(posts: List<Post>): List<Post> {
        val friendIds = _friends.value.map { it.id }.toSet()
        val ranked = feedRankingEngine.rankPosts(
            posts = posts,
            currentUserId = authService.getCurrentUserId(),
            friendIds = friendIds,
            signalSnapshot = feedSignalTracker.snapshot(),
            interestTerms = profileInterestTerms()
        )
        return ranked
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
        feedSignalTracker.recordInteraction(post, FeedInteractionAction.IMPRESSION)
    }

    fun selectFeedMode(mode: SocialFeedMode) {
        if (feedMode.value == mode) return
        feedPreferences.setMode(mode)
        loadPosts()
    }

    fun explainPost(post: Post): List<String> = feedRankingEngine.explainPost(
        post = post,
        currentUserId = authService.getCurrentUserId(),
        friendIds = _friends.value.map { it.id }.toSet(),
        signalSnapshot = feedSignalTracker.snapshot(),
        interestTerms = profileInterestTerms()
    )

    fun hideCreator(post: Post) {
        feedPreferences.muteCreator(post.userId)
        _posts.value = applyFeedControls(_posts.value)
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
    fun createPost(content: String, uris: List<android.net.Uri>, visibility: String = "public") {
        viewModelScope.launch {
            try {
                val result = postRepository.createPost(
                    content = content,
                    mediaUris = uris,
                    visibility = visibility
                )
                if (result.isSuccess) {
                    val created = result.getOrThrow()
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
