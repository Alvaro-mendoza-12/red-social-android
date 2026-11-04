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
import conectate.com.presentation.posts.PostReactionWriter
import conectate.com.presentation.posts.copyReactionFrom
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
    private val reactionWriter = PostReactionWriter(viewModelScope, authService::getCurrentUserId,
        { id -> _posts.value.firstOrNull { it.id == id } },
        postRepository::setPostReaction, postRepository::getPostById,
        { fresh -> _posts.value = _posts.value.map { if (it.id == fresh.id) it.copyReactionFrom(fresh) else it } },
        { conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo guardar la reacción. Reintenta.") })

    init {
        viewModelScope.launch {
            postRepository.reactionChanges.collect { change ->
                if (change.actorId == authService.getCurrentUserId()) reactionWriter.acceptConfirmed(change.postId, change.reaction)
            }
        }
        viewModelScope.launch {
            shareRepository.postChanges.collect { change ->
                if (change.actorId == authService.getCurrentUserId()) _posts.value = _posts.value.map(change::applyTo)
            }
        }
        viewModelScope.launch {
            authService.currentSession.map { it?.user?.id }.distinctUntilChanged().collect {
                reactionWriter.clear()
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
            val reactionReadVersion = reactionWriter.readVersion()
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
                postResult.onSuccess { rows -> _posts.value = rows.map { reactionWriter.reconcile(it, reactionReadVersion) } }
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
        reactionWriter.set(post.id, reactionType)
    }

    fun togglePostLike(post: Post) = reactionWriter.toggleLike(post.id)

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
