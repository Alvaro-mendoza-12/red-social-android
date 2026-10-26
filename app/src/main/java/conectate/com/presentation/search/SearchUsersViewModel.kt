package conectate.com.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.PostRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SearchUsersViewModel @Inject constructor(
    userRepository: UserRepository,
    postRepository: PostRepository,
    private val authService: AuthService,
    private val historyStore: SearchHistoryStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val search = UnifiedSearchController(
        scope = viewModelScope,
        findUsers = { query -> userRepository.searchUsers(query).map { it.distinctBy { user -> user.id } } },
        findContent = { query -> postRepository.searchPublicContent(query).map { it.distinctBy { item -> "${item.type}:${item.id}" } } },
        findContentPage = { query, offset -> postRepository.searchPublicContentPage(query, offset = offset)
            .map { SearchContentPage(it.items, it.nextOffset) } },
        contentKey = { "${it.type}:${it.id}" },
    )
    val searchResults = search.users
    val contentResults = search.content
    val isLoading = search.loading
    val userErrorMessage = search.usersError
    val errorMessage = search.contentError
    val isLoadingMore = search.loadingMore
    val hasMoreContent = search.hasMoreContent
    private val _query = MutableStateFlow(searchTerm(savedStateHandle.get<String>("query").orEmpty()))
    val query = _query.asStateFlow()
    private val _accountId = MutableStateFlow(authService.getCurrentUserId())
    val accountId = _accountId.asStateFlow()
    private val _history = MutableStateFlow(historyStore.read(_accountId.value))
    val history = _history.asStateFlow()
    internal val suggestions = combine(_query, search.content, _history, ::relatedSearchSuggestions)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    internal val hashtags = search.content.combine(_query) { content, _ -> searchHashtags(content) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val initialCategory = if (_query.value.isNotBlank()) 1 else 0

    init {
        if (_query.value.isNotBlank()) submitSearch(_query.value)
        viewModelScope.launch {
            authService.currentSession.collect {
                synchronizeAccount()
            }
        }
    }

    fun searchUsers(query: String) {
        if (synchronizeAccount()) return
        _query.value = searchInput(query)
        search.search(_query.value)
    }

    fun submitSearch(query: String = _query.value) {
        if (synchronizeAccount()) return
        val clean = searchTerm(query)
        _query.value = clean
        search.search(clean, immediate = true)
        if (_accountId.value != null) {
            _history.value = updatedSearchHistory(_history.value, clean)
            historyStore.write(_accountId.value, _history.value)
        }
    }

    fun removeHistory(query: String) {
        if (synchronizeAccount()) return
        _history.value = _history.value.filterNot { searchTermKey(it) == searchTermKey(query) }
        historyStore.write(_accountId.value, _history.value)
    }

    fun clearHistory() {
        if (synchronizeAccount()) return
        _history.value = emptyList()
        historyStore.write(_accountId.value, emptyList())
    }

    fun loadMore() { if (!synchronizeAccount()) search.loadMoreContent() }
    fun clearSearch() { _query.value = ""; search.clear() }

    private fun synchronizeAccount(): Boolean {
        val account = authService.getCurrentUserId()
        if (account != _accountId.value) {
            search.clear()
            _accountId.value = account
            _query.value = ""
            _history.value = historyStore.read(account)
            return true
        }
        return false
    }
}
