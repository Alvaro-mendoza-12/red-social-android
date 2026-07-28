package conectate.com.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.User
import conectate.com.data.model.SemanticSearchResult
import conectate.com.data.repository.PostRepository
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
    private val userRepository: UserRepository,
    private val postRepository: PostRepository
) : ViewModel() {

    private val _searchResults = MutableStateFlow<List<User>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _contentResults = MutableStateFlow<List<SemanticSearchResult>>(emptyList())
    val contentResults = _contentResults.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

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
                delay(350)
                _errorMessage.value = null

                val usersResult = userRepository.searchUsers(query)
                usersResult.onSuccess { users ->
                    _searchResults.value = users
                }.onFailure { e ->
                    android.util.Log.w("SearchViewModel", "User search failed", e)
                    _searchResults.value = emptyList()
                }
                postRepository.searchPublicContent(query)
                    .onSuccess { items -> _contentResults.value = items }
                    .onFailure { error ->
                        android.util.Log.w("SearchViewModel", "Semantic search failed", error)
                        _contentResults.value = emptyList()
                        _errorMessage.value = when {
                            error.message?.contains("SEMANTIC_SEARCH_UNAVAILABLE", ignoreCase = true) == true ->
                                "La búsqueda de contenido no está disponible por el momento."
                            else -> "No pudimos buscar contenido. Revisa tu conexión e inténtalo otra vez."
                        }
                    }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("SearchViewModel", "Search failed", e)
                _searchResults.value = emptyList()
                _contentResults.value = emptyList()
                _errorMessage.value = "No pudimos completar la búsqueda."
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _contentResults.value = emptyList()
        _errorMessage.value = null
        _isLoading.value = false
    }
}
