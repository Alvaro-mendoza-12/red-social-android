package conectate.com.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.UpdateProfileRequest
import conectate.com.data.model.User
import conectate.com.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AdminUserUiState {
    object Loading : AdminUserUiState()
    data class Success(val users: List<User>) : AdminUserUiState()
    data class Error(val message: String) : AdminUserUiState()
}

@HiltViewModel
class AdminUserPanelViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AdminUserUiState>(AdminUserUiState.Loading)
    val uiState: StateFlow<AdminUserUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        loadUsers()
    }

    fun loadUsers() {
        viewModelScope.launch {
            _uiState.value = AdminUserUiState.Loading
            userRepository.getAllUsers(limit = 100).fold(
                onSuccess = { users -> _uiState.value = AdminUserUiState.Success(users) },
                onFailure = { error -> _uiState.value = AdminUserUiState.Error(error.message ?: "Error desconocido") }
            )
        }
    }

    fun searchUsers(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            loadUsers()
            return
        }

        viewModelScope.launch {
            _uiState.value = AdminUserUiState.Loading
            userRepository.searchUsers(query).fold(
                onSuccess = { _uiState.value = AdminUserUiState.Success(it) },
                onFailure = { _uiState.value = AdminUserUiState.Error(it.message ?: "Error al buscar") }
            )
        }
    }

    fun updateUserBalance(userId: String, newBalance: Double) {
        viewModelScope.launch {
            val result = userRepository.updateProfile(userId, UpdateProfileRequest(balance = newBalance))
            if (result.isSuccess) {
                // Refresh the list if it's currently showing success
                val currentState = _uiState.value
                if (currentState is AdminUserUiState.Success) {
                    val updatedUsers = currentState.users.map {
                        if (it.id == userId) it.copy(balance = newBalance) else it
                    }
                    _uiState.value = AdminUserUiState.Success(updatedUsers)
                }
            }
        }
    }
}
