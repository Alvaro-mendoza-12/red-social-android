package conectate.com.presentation.ranking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Post
import conectate.com.data.model.User
import conectate.com.data.model.Video
import conectate.com.data.repository.RankingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.*
import conectate.com.data.repository.VideoRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RankingViewModel @Inject constructor(
    private val repository: RankingRepository,
    private val videoRepository: VideoRepository
) : ViewModel() {

    private val _users = MutableStateFlow<List<User>>(emptyList())
    val users: StateFlow<List<User>> = _users.asStateFlow()

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> = _videos.asStateFlow()

    private val _posts = MutableStateFlow<List<Post>>(emptyList())
    val posts: StateFlow<List<Post>> = _posts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.getTopUsers().onSuccess { _users.value = it }
                repository.getTopPosts().onSuccess { _posts.value = it }
                repository.getTopVideos().onSuccess { baseVideos ->
                    _videos.value = baseVideos
                    
                    // Observe stats for each video in real-time from local Room cache
                    baseVideos.forEach { video ->
                        viewModelScope.launch {
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
            } catch (e: Exception) {
                android.util.Log.e("RankingViewModel", "Error loading ranking data", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
}
