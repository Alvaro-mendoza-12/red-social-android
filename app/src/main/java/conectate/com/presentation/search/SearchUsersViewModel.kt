package conectate.com.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.PostRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var visit = authService.captureAccountLease()
    private val search = UnifiedSearchController(
        scope = viewModelScope,
        findUsers = { query -> forCurrentVisit { userRepository.searchUsers(query) }
            .map { it.distinctBy { user -> user.id } } },
        findContent = { query -> forCurrentVisit { postRepository.searchPublicContent(query) }
            .map { it.distinctBy { item -> "${item.type}:${item.id}" } } },
        findContentPage = { query, offset -> forCurrentVisit { postRepository.searchPublicContentPage(query, offset = offset) }
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
    private val _accountId = MutableStateFlow(visit.accountId)
    val accountId = _accountId.asStateFlow()
    private val _history = MutableStateFlow(historyStore.read(_accountId.value))
    val history = _history.asStateFlow()
    internal val discovery = observeSearchDiscovery(_query, search.content, _history)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), SearchDiscovery())
    val initialCategory = if (_query.value.isNotBlank()) 1 else 0

    init {
        if (_query.value.isNotBlank()) submitSearch(_query.value)
        viewModelScope.launch {
            combine(authService.currentSession, authService.accountVisitChanges) { _, _ -> Unit }
                .collect { synchronizeAccount() }
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
        updateHistory { updatedSearchHistory(it, clean) }
    }

    fun removeHistory(query: String) {
        if (synchronizeAccount()) return
        updateHistory { history -> history.filterNot { searchTermKey(it) == searchTermKey(query) } }
    }

    fun clearHistory() {
        if (synchronizeAccount()) return
        updateHistory { emptyList() }
    }

    fun loadMore() { if (!synchronizeAccount()) search.loadMoreContent() }
    fun clearSearch() {
        if (synchronizeAccount()) return
        _query.value = ""
        search.clear()
    }

    private fun updateHistory(update: (List<String>) -> List<String>) {
        val owner = visit
        authService.commitForAccountLease(owner) {
            val updated = update(_history.value)
            if (updated != _history.value) {
                historyStore.write(owner.accountId, updated)
                _history.value = updated
            }
        }
    }

    private suspend fun <T> forCurrentVisit(read: suspend () -> Result<T>): Result<T> {
        val owner = visit
        owner.requireCurrent()
        val result = read()
        currentCoroutineContext().ensureActive()
        owner.requireCurrent()
        return result
    }

    private fun synchronizeAccount(): Boolean {
        val current = authService.captureAccountLease()
        if (visit.sameVisit(current)) return false
        visit = current
        search.clear()
        _query.value = ""
        _history.value = historyStore.read(current.accountId)
        _accountId.value = current.accountId
        return true
    }
}
