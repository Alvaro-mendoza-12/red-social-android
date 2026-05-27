package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.GroupWithLastMessage
import conectate.com.data.repository.GroupRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class GroupsUiState {
    object Loading : GroupsUiState()
    data class Success(val groups: List<GroupWithLastMessage>) : GroupsUiState()
    data class Error(val message: String) : GroupsUiState()
}

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val authService: AuthService
) : ViewModel() {

    private val _groupsState = MutableStateFlow<GroupsUiState>(GroupsUiState.Loading)
    val groupsState: StateFlow<GroupsUiState> = _groupsState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Filtered groups based on search query
    val filteredGroups: StateFlow<List<GroupWithLastMessage>> = combine(
        groupRepository.getGroupsFlow(),
        _searchQuery
    ) { groups, query ->
        if (query.isBlank()) {
            groups
        } else {
            groups.filter { 
                it.group.name.contains(query, ignoreCase = true) || 
                (it.group.description?.contains(query, ignoreCase = true) ?: false)
            }
        }
    }.map { groups -> 
        groups.distinctBy { it.group.id } 
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentUserId: String? get() = authService.getCurrentUserId()

    init {
        refreshGroups()
        
        // Update groups state based on flow
        viewModelScope.launch {
            groupRepository.getGroupsFlow().collect { groups ->
                android.util.Log.d("GroupsViewModel", "[FLOW] Received ${groups.size} groups from repository")
                if (groups.isNotEmpty()) {
                    val first = groups.first()
                    android.util.Log.d("GroupsViewModel", "[FLOW] First group: ${first.group.name}, last_msg: ${first.group.last_message}")
                }
                _groupsState.value = GroupsUiState.Success(groups)
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refreshGroups() {
        viewModelScope.launch {
            val result = groupRepository.syncGroups()
            if (result.isFailure) {
                // We don't necessarily want to hide the UI on sync failure if we have cache
                // But we could log it or show a temporary snackbar
            }
        }
    }

    fun createGroup(name: String, description: String?, imageBytes: ByteArray?, members: List<String>) {
        viewModelScope.launch {
            groupRepository.createGroup(name, description, imageBytes, members)
        }
    }
}
