package conectate.com.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.User
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadCurrentUser()
    }

    private fun loadCurrentUser() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val userId = authRepository.getCurrentUserId()
                if (userId != null) {
                    userRepository.getUserById(userId).onSuccess { user ->
                        _user.value = user
                    }
                }
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                authRepository.signOut()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            try {
                // userRepository.deleteAccount() // Not implemented yet or moved
            } catch (e: Exception) {
                // Handle error
            }
        }
    }
}
