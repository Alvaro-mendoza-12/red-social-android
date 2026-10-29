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

    private val userId: String = savedStateHandle.get<String>("userId").orEmpty()
    private val type: String = savedStateHandle.get<String>("type").orEmpty() // "followers", "following", "friends"

    private val _uiState = MutableStateFlow<FollowListUiState>(FollowListUiState.Loading)
    val uiState: StateFlow<FollowListUiState> = _uiState.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null
    init {
        loadData()
    }

    fun loadData() {
        loadJob?.cancel()
        if (userId.isBlank()) { _uiState.value = FollowListUiState.Error("El enlace del perfil no es válido."); return }
        loadJob = viewModelScope.launch {
            _uiState.value = FollowListUiState.Loading
            
            val result = conectate.com.core.network.runReadWithDeadline(10_000) { (when (type) {
                "followers" -> userRepository.getFollowers(userId)
                "following" -> userRepository.getFollowing(userId)
                "friends" -> friendRepository.getFriends(userId)
                else -> Result.failure(Exception("Invalid type"))
            }).getOrThrow() }
            result.onSuccess { users ->
                _uiState.value = FollowListUiState.Success(users)
            }.onFailure { e ->
                _uiState.value = FollowListUiState.Error(e.message ?: "Error al cargar la lista")
            }
        }
    }
}
