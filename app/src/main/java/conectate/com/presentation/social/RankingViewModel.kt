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

    init {
        loadRankings()
    }

    fun loadRankings() {
        viewModelScope.launch {
            _isLoading.value = true
            
            launch {
                rankingRepository.getTopUsers().onSuccess { _topUsers.value = it }
            }
            launch {
                rankingRepository.getTopVideos().onSuccess { _topVideos.value = it }
            }
            launch {
                rankingRepository.getTopPosts().onSuccess { _topPosts.value = it }
            }
            
            _isLoading.value = false
        }
    }
}
