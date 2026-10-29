package conectate.com.presentation.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Post
import conectate.com.data.model.User
import conectate.com.data.model.Video
import conectate.com.data.repository.RankingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import conectate.com.core.network.runReadWithDeadline
import javax.inject.Inject

@HiltViewModel
class RankingViewModel @Inject constructor(
    private val rankingRepository: RankingRepository
) : ViewModel() {

    private val _topUsers = MutableStateFlow<List<User>>(emptyList())
    val topUsers = _topUsers.asStateFlow()

    private val _topVideos = MutableStateFlow<List<Video>>(emptyList())
    val topVideos = _topVideos.asStateFlow()

    private val _topPosts = MutableStateFlow<List<Post>>(emptyList())
    val topPosts = _topPosts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var loadJob: Job? = null

    init {
        loadRankings()
    }

    fun loadRankings() {
        if (loadJob?.isActive == true) return
        _isLoading.value = true
        _error.value = null
        loadJob = viewModelScope.launch {
            try {
                val (users, videos, posts) = readRankings(
                    { rankingRepository.getTopUsers() }, { rankingRepository.getTopVideos() }, { rankingRepository.getTopPosts() })
                users.onSuccess { _topUsers.value = it }
                videos.onSuccess { _topVideos.value = it }
                posts.onSuccess { _topPosts.value = it }
                if (users.isFailure || videos.isFailure || posts.isFailure) _error.value = "No se pudo cargar parte del ranking. Reintenta."
            } finally { _isLoading.value = false }
        }
    }
}

internal suspend fun readRankings(users: suspend () -> Result<List<User>>, videos: suspend () -> Result<List<Video>>,
                                  posts: suspend () -> Result<List<Post>>) = coroutineScope {
    val u = async { runReadWithDeadline(10_000) { users().getOrThrow() } }
    val v = async { runReadWithDeadline(10_000) { videos().getOrThrow() } }
    val p = async { runReadWithDeadline(10_000) { posts().getOrThrow() } }
    Triple(u.await(), v.await(), p.await())
}
