package conectate.com.presentation.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.User
import conectate.com.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class FollowListUiState {
    object Loading : FollowListUiState()
    data class Success(val users: List<User>) : FollowListUiState()
    data class Error(val message: String) : FollowListUiState()
}

@HiltViewModel
class FollowListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val userRepository: UserRepository,
    private val friendRepository: conectate.com.data.repository.FriendRepository
) : ViewModel() {

    private val userId: String = checkNotNull(savedStateHandle["userId"])
    private val type: String = checkNotNull(savedStateHandle["type"]) // "followers", "following", "friends"

    private val _uiState = MutableStateFlow<FollowListUiState>(FollowListUiState.Loading)
    val uiState: StateFlow<FollowListUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = FollowListUiState.Loading
            
            val result = when (type) {
                "followers" -> userRepository.getFollowers(userId)
                "following" -> userRepository.getFollowing(userId)
                "friends" -> friendRepository.getFriends(userId)
                else -> Result.failure(Exception("Invalid type"))
            }
            
            result.onSuccess { users ->
                _uiState.value = FollowListUiState.Success(users)
            }.onFailure { e ->
                _uiState.value = FollowListUiState.Error(e.message ?: "Error al cargar la lista")
            }
        }
    }
}
