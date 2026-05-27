package conectate.com.presentation.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.core.feed.FeedRankingEngine
import conectate.com.core.feed.FeedInteractionAction
import conectate.com.core.feed.FeedSignalTracker
import conectate.com.core.observability.AppMetrics
import conectate.com.data.model.Post
import conectate.com.data.repository.PostRepository
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
    private val appMetrics: AppMetrics,
    private val authService: conectate.com.data.supabase.AuthService,
    private val shareRepository: conectate.com.data.repository.ShareRepository,
    // challengeRepository removed (moved to ChallengeViewModel)
    private val friendRepository: conectate.com.data.repository.FriendRepository,
    val userRepository: conectate.com.data.repository.UserRepository,
    val voiceMessageManager: conectate.com.presentation.messages.VoiceMessageManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {
    
    init {
        voiceMessageManager.setScope(viewModelScope)
    }

    private var submissionsJob: kotlinx.coroutines.Job? = null
    private var countdownJob: kotlinx.coroutines.Job? = null

    private val _currentUser = MutableStateFlow<conectate.com.data.model.User?>(null)
    val currentUser: StateFlow<conectate.com.data.model.User?> = _currentUser.asStateFlow()

    private val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = _posts.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _friends = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val friends: StateFlow<List<conectate.com.data.model.User>> = _friends.asStateFlow()
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
                postRepository.getGlobalPosts().collect { globalPosts ->
                    val ranked = rankPosts(globalPosts)
                    _posts.value = ranked
                    feedSignalTracker.recordImpressions(ranked)
                    appMetrics.incrementCounter(
                        metric = "feed_load_success",
                        attributes = mapOf("count" to ranked.size.toString())
                    )
                    appMetrics.recordLatency(
                        metric = "feed_load",
                        durationMs = System.currentTimeMillis() - startedAt,
                        attributes = mapOf("count" to ranked.size.toString())
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

    private fun rankPosts(posts: List<Post>): List<Post> {
        val friendIds = _friends.value.map { it.id }.toSet()
        return feedRankingEngine.rankPosts(
            posts = posts,
            currentUserId = authService.getCurrentUserId(),
            friendIds = friendIds,
            signalSnapshot = feedSignalTracker.snapshot()
        )
    }

    fun toggleLike(post: Post) {
        val currentReaction = post.myReaction?.uppercase()?.ifBlank { null }
            ?: if (post.likedByMe) "LIKE" else null
        if (currentReaction != null) {
            clearReaction(post)
        } else {
            selectReaction(post, "LIKE")
        }
    }

    fun selectReaction(post: Post, reactionType: String) {
        updatePostReaction(post, reactionType)
    }

    fun clearReaction(post: Post) {
        updatePostReaction(post, null)
    }

    private fun updatePostReaction(post: Post, reactionType: String?) {
        if (!likeRequestsInFlight.add(post.id)) return
        viewModelScope.launch {
            val previousReaction = post.myReaction?.uppercase()?.ifBlank { null }
                ?: if (post.likedByMe) "LIKE" else null
            val nextReaction = reactionType?.uppercase()?.ifBlank { null }
            try {
                _posts.value = _posts.value.map {
                    if (it.id == post.id) applyReactionState(it, previousReaction, nextReaction) else it
                }
                if (previousReaction == null && nextReaction != null) {
                    feedSignalTracker.recordInteraction(post = post, action = FeedInteractionAction.LIKE)
                } else if (previousReaction != null && nextReaction == null) {
                    feedSignalTracker.recordInteraction(post = post, action = FeedInteractionAction.UNLIKE)
                }

                val result = postRepository.setPostReaction(
                    postId = post.id,
                    reactionType = nextReaction,
                    wasReacted = previousReaction != null
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

    fun togglePostShare(post: Post) {
        if (!shareRequestsInFlight.add(post.id)) return
        viewModelScope.launch {
            try {
                val userId = authService.getCurrentUserId() ?: return@launch
                val wasShared = post.sharedByMe
                
                android.util.Log.d("SocialViewModel", "togglePostShare: wasShared=$wasShared, postId=${post.id}")
                
                // Optimistic Update
                _posts.value = _posts.value.map {
                    if (it.id == post.id) it.copy(sharedByMe = !wasShared) else it
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
                    android.widget.Toast.makeText(context, "Error: ${result.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_SHORT).show()
                    // Revert on failure
                    _posts.value = _posts.value.map {
                        if (it.id == post.id) it.copy(sharedByMe = wasShared) else it
                    }
                } else {
                    android.util.Log.d("SocialViewModel", "togglePostShare SUCCESS")
                    val message = if (wasShared) "Eliminado de tu perfil" else "Añadido a tu perfil"
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("SocialViewModel", "Error toggling post share", e)
                android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
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
                    loadPosts() // Refresh
                    android.widget.Toast.makeText(context, "Publicación creada", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val reason = result.exceptionOrNull()?.message ?: "Error desconocido"
                    android.widget.Toast.makeText(context, "Error al publicar: $reason", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("SocialViewModel", "Error creating post", e)
                android.widget.Toast.makeText(context, "Error al publicar", android.widget.Toast.LENGTH_SHORT).show()
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
