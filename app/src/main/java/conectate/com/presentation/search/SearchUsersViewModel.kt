package conectate.com.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.PostRepository
import conectate.com.data.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SearchUsersViewModel @Inject constructor(
    userRepository: UserRepository,
    postRepository: PostRepository,
) : ViewModel() {
    private val search = UnifiedSearchController(
        scope = viewModelScope,
        findUsers = { query -> userRepository.searchUsers(query).map { it.distinctBy { user -> user.id } } },
        findContent = { query -> postRepository.searchPublicContent(query).map { it.distinctBy { item -> "${item.type}:${item.id}" } } },
    )
    val searchResults = search.users
    val contentResults = search.content
    val isLoading = search.loading
    val userErrorMessage = search.usersError
    val errorMessage = search.contentError

    fun searchUsers(query: String) = search.search(query)
    fun clearSearch() = search.clear()
}
