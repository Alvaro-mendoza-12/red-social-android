package conectate.com.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.User
import conectate.com.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchUsersViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _searchResults = MutableStateFlow<List<User>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private var searchJob: Job? = null

    fun searchUsers(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            clearSearch()
            return
        }

        searchJob = viewModelScope.launch {
            try {
                _isLoading.value = true
                delay(500) // Debounce
                
                val result = userRepository.searchUsers(query)
                result.onSuccess { users ->
                    _searchResults.value = users
                }.onFailure { e ->
                    // Log error (in real app use Timber/etc)
                    println("Search error: ${e.message}")
                    _searchResults.value = emptyList()
                }
            } catch (e: Exception) {
                println("Search crash: ${e.message}")
                _searchResults.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearSearch() {
        _searchResults.value = emptyList()
    }
}
