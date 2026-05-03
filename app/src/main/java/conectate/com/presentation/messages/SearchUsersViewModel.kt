package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.User
import conectate.com.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.Result

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchUsersViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        _searchQuery
            .debounce(300L)
            .distinctUntilChanged()
            .filter { it.length >= 3 }
            .onEach { query ->
                performSearch(query)
            }
            .launchIn(viewModelScope)
    }

    fun onQueryChange(query: String) {
        _searchQuery.value = query
        if (query.length < 3) {
            _uiState.value = SearchUiState.Idle
        }
    }

    private fun performSearch(query: String) {
        viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            userRepository.searchUsers(query)
                .onSuccess { users ->
                    _uiState.value = if (users.isEmpty()) {
                        SearchUiState.Empty
                    } else {
                        SearchUiState.Success(users)
                    }
                }
                .onFailure { e ->
                    _uiState.value = SearchUiState.Error(e.message ?: "Error desconocido")
                }
        }
    }
}

sealed class SearchUiState {
    object Idle : SearchUiState()
    object Loading : SearchUiState()
    data class Success(val users: List<User>) : SearchUiState()
    object Empty : SearchUiState()
    data class Error(val message: String) : SearchUiState()
}
