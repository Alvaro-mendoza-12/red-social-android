package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.CommunityWithLastMessage
import conectate.com.data.repository.CommunityRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CommunitiesUiState {
    object Loading : CommunitiesUiState()
    data class Success(val communities: List<CommunityWithLastMessage>) : CommunitiesUiState()
    data class Error(val message: String) : CommunitiesUiState()
}

@HiltViewModel
class CommunitiesViewModel @Inject constructor(
    private val communityRepository: CommunityRepository,
    private val authService: AuthService
) : ViewModel() {
    val currentUserId: String? get() = authService.getCurrentUserId()

    private val _communitiesState = MutableStateFlow<CommunitiesUiState>(CommunitiesUiState.Loading)
    val communitiesState: StateFlow<CommunitiesUiState> = _communitiesState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _globalSearchResults = MutableStateFlow<List<CommunityWithLastMessage>>(emptyList())
    val globalSearchResults: StateFlow<List<CommunityWithLastMessage>> = _globalSearchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val filteredCommunities: StateFlow<List<CommunityWithLastMessage>> = combine(
        communityRepository.getDiscoverableCommunitiesFlow(),
        _searchQuery
    ) { communities, query ->
        if (query.isBlank()) {
            communities
        } else {
            communities.filter { 
                it.community.name.contains(query, ignoreCase = true) || 
                (it.community.description?.contains(query, ignoreCase = true) ?: false)
            }
        }
    }
        .catch { error ->
            android.util.Log.e("CommunitiesViewModel", "Error cargando comunidades", error)
            _communitiesState.value = CommunitiesUiState.Error(
                error.message ?: "No se pudieron cargar las comunidades."
            )
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val combinedCommunities: StateFlow<List<CommunityWithLastMessage>> = combine(
        filteredCommunities,
        _globalSearchResults
    ) { filtered, global ->
        (filtered + global).distinctBy { it.community.id } 
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), emptyList())

    init {
        refreshCommunities()
        refreshExploreSnapshot()
        
        viewModelScope.launch {
            communityRepository.getDiscoverableCommunitiesFlow()
                .catch { error ->
                    android.util.Log.e("CommunitiesViewModel", "Flow de comunidades falló", error)
                    _communitiesState.value = CommunitiesUiState.Error(
                        error.message ?: "No se pudieron cargar las comunidades."
                    )
                    emit(emptyList())
                }
                .collect { communities ->
                android.util.Log.d("CommunitiesViewModel", "[FLOW] Received ${communities.size} communities from repository")
                if (communities.isNotEmpty()) {
                    val first = communities.first()
                    android.util.Log.d("CommunitiesViewModel", "[FLOW] First community: ${first.community.name}, last_msg: ${first.community.last_message}")
                }
                _communitiesState.value = CommunitiesUiState.Success(communities)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.length >= 2) {
            searchGlobal(query)
        } else if (query.isBlank()) {
            refreshExploreSnapshot()
        } else {
            _isSearching.value = false
        }
    }

    private fun searchGlobal(query: String) {
        viewModelScope.launch {
            _isSearching.value = true
            try {
                val result = communityRepository.searchCommunities(query)
                if (result.isSuccess) {
                    val searchItems = result.getOrNull()?.map { 
                        CommunityWithLastMessage(community = it, unreadCount = 0)
                    } ?: emptyList()
                    _globalSearchResults.value = searchItems
                } else {
                    android.util.Log.w("CommunitiesViewModel", "Búsqueda de comunidades falló: ${result.exceptionOrNull()?.message}")
                }
            } catch (error: Exception) {
                android.util.Log.e("CommunitiesViewModel", "Error buscando comunidades", error)
            } finally {
                _isSearching.value = false
            }
        }
    }

    private fun refreshExploreSnapshot() {
        viewModelScope.launch {
            _isSearching.value = true
            try {
                val result = communityRepository.searchCommunities("")
                if (result.isSuccess) {
                    _globalSearchResults.value = result.getOrDefault(emptyList())
                        .filter { !it.is_deleted }
                        .map { CommunityWithLastMessage(community = it, unreadCount = 0) }
                } else {
                    android.util.Log.w("CommunitiesViewModel", "Snapshot explorar falló: ${result.exceptionOrNull()?.message}")
                }
            } catch (error: Exception) {
                android.util.Log.e("CommunitiesViewModel", "Error refrescando explorar", error)
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun joinCommunity(communityId: String) {
        viewModelScope.launch {
            communityRepository.joinCommunity(communityId)
            _globalSearchResults.value = emptyList() // Clear search after joining
            _searchQuery.value = ""
            refreshCommunities()
        }
    }

    fun refreshCommunities() {
        viewModelScope.launch {
            try {
                communityRepository.syncCommunities()
                if (_searchQuery.value.isBlank()) {
                    refreshExploreSnapshot()
                }
            } catch (error: Exception) {
                android.util.Log.e("CommunitiesViewModel", "Error sincronizando comunidades", error)
                _communitiesState.value = CommunitiesUiState.Error(
                    error.message ?: "No se pudieron sincronizar las comunidades."
                )
            }
        }
    }
}
