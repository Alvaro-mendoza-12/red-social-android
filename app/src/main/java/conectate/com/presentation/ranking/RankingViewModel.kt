package conectate.com.presentation.ranking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Post
import conectate.com.data.model.User
import conectate.com.data.model.Video
import conectate.com.data.repository.RankingRepository
import conectate.com.data.repository.PostRepository
import conectate.com.data.repository.ShareRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.*
import conectate.com.data.repository.VideoRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

@HiltViewModel
class RankingViewModel @Inject constructor(
    private val repository: RankingRepository,
    private val videoRepository: VideoRepository,
    private val postRepository: PostRepository,
    private val shareRepository: ShareRepository,
    private val authService: AuthService
) : ViewModel() {

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()

    private val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = _posts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _usersMetric = MutableStateFlow("followers")
    val usersMetric = _usersMetric.asStateFlow()
    private val _myRank = MutableStateFlow<Int?>(null)
    val myRank = _myRank.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var loadJob: Job? = null
    private var statsJob: Job? = null
    private var loadRevision = 0L
    private val postMutationsInFlight = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            shareRepository.postChanges.collect { change ->
                if (change.actorId == authService.getCurrentUserId()) _posts.value = _posts.value.map(change::applyTo)
            }
        }
        viewModelScope.launch {
            authService.currentSession.map { it?.user?.id }.distinctUntilChanged().collect {
                _users.value = emptyList()
                _videos.value = emptyList()
                _posts.value = emptyList()
                _myRank.value = null
                loadData()
            }
        }
    }

    fun selectUsersMetric(metric: String) {
        if (metric !in setOf("followers", "following", "posts") || metric == _usersMetric.value) return
        _usersMetric.value = metric
        loadData()
    }

    fun loadData() {
        val revision = ++loadRevision
        loadJob?.cancel()
        statsJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                coroutineScope {
                val userRequest = async { repository.getUserRanking(_usersMetric.value) }
                val postRequest = async { repository.getTopPosts() }
                val videoRequest = async { repository.getTopVideos() }
                val userResult = userRequest.await()
                val postResult = postRequest.await()
                val videoResult = videoRequest.await()
                if (revision != loadRevision) return@coroutineScope
                userResult.onSuccess { _users.value = it.users; _myRank.value = it.myRank }
                postResult.onSuccess { _posts.value = it }
                if (listOf(userResult, postResult, videoResult).any { it.isFailure }) {
                    _error.value = "No se pudo actualizar todo el ranking. Puedes reintentar."
                }
                videoResult.onSuccess { baseVideos ->
                    _videos.value = baseVideos
                    
                    // Observe stats for each video in real-time from local Room cache
                    statsJob = viewModelScope.launch {
                    baseVideos.forEach { video ->
                        launch {
                            videoRepository.getVideoStatsFlow(video.id).collect { stats ->
                                if (stats != null) {
                                    _videos.value = _videos.value.map { v ->
                                        if (v.id == video.id) {
                                            v.copy(
                                                views = stats.viewCount,
                                                likeCount = stats.likeCount,
                                                commentCount = stats.commentCount,
                                                shareCount = stats.shareCount
                                            )
                                        } else v
                                    }
                                }
                            }
                        }
                    }
                    }
                }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("RankingViewModel", "Error loading ranking data", e)
                _error.value = "No se pudo actualizar el ranking."
            } finally {
                if (revision == loadRevision) _isLoading.value = false
            }
        }
    }

    fun setPostReaction(post: Post, reactionType: String?) {
        val postId = post.id.trim()
        if (postId.isBlank() || !postMutationsInFlight.add("reaction:$postId")) return

        viewModelScope.launch {
            val previousReaction = post.myReaction ?: if (post.likedByMe) "LIKE" else null
            val normalizedReaction = reactionType?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            val previousLiked = previousReaction != null
            val nextLiked = normalizedReaction != null
            val nextLikeCount = when {
                !previousLiked && nextLiked -> post.likeCount + 1
                previousLiked && !nextLiked -> maxOf(0, post.likeCount - 1)
                else -> post.likeCount
            }
            updatePost(postId) {
                it.copy(
                    likedByMe = nextLiked,
                    myReaction = normalizedReaction,
                    likeCount = nextLikeCount,
                )
            }

            val result = postRepository.setPostReaction(
                postId = postId,
                reactionType = normalizedReaction,
                wasReacted = previousLiked,
            )
            if (result.isFailure) {
                updatePost(postId) {
                    it.copy(
                        likedByMe = previousLiked,
                        myReaction = previousReaction,
                        likeCount = post.likeCount,
                    )
                }
            }
            postMutationsInFlight.remove("reaction:$postId")
        }
    }

    fun togglePostShare(post: Post) {
        val postId = post.id.trim()
        if (postId.isBlank() || !postMutationsInFlight.add("share:$postId")) return

        viewModelScope.launch {
            val userId = authService.getCurrentUserId()
            if (userId.isNullOrBlank()) {
                postMutationsInFlight.remove("share:$postId")
                return@launch
            }
            val wasShared = post.sharedByMe
            updatePost(postId) {
                it.copy(
                    sharedByMe = !wasShared,
                    shareCount = if (wasShared) maxOf(0, it.shareCount - 1) else it.shareCount + 1,
                )
            }

            val result = if (wasShared) {
                shareRepository.unshareContent(userId, "post", postId)
            } else {
                shareRepository.shareContent(userId, "post", postId)
            }
            if (result.isFailure) updatePost(postId) { it.copy(sharedByMe = wasShared, shareCount = post.shareCount) }
            postMutationsInFlight.remove("share:$postId")
        }
    }

    private fun updatePost(postId: String, transform: (Post) -> Post) {
        _posts.value = _posts.value.map { post ->
            if (post.id == postId) transform(post) else post
        }
    }
}
