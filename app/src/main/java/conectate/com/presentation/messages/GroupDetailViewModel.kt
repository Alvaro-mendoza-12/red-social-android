package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Group
import conectate.com.data.model.GroupMember
import conectate.com.data.repository.GroupRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    private val groupRepository: GroupRepository,
    private val authService: AuthService,
    private val userRepository: conectate.com.data.repository.UserRepository
) : ViewModel() {

    private val _group = MutableStateFlow<Group?>(null)
    val group = _group.asStateFlow()

    private val _members = MutableStateFlow<List<GroupMember>>(emptyList())
    val members = _members.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId = _currentUserId.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin = _isAdmin.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted = _isDeleted.asStateFlow()

    private var groupId: String? = null

    init {
        val userId = authService.getCurrentUserId()
        _currentUserId.value = userId
    }

    fun loadGroupDetails(id: String) {
        groupId = id
        _isLoading.value = true
        
        // 1. Force Sync (Fire and forget)
        viewModelScope.launch {
             try { groupRepository.syncGroups() } catch (e: Exception) {}
        }

        // 2. Observe Group
        viewModelScope.launch {
            groupRepository.getGroupFlow(id).collect { grp ->
                if (grp != null) {
                    if (grp.is_deleted) _isDeleted.value = true
                    _group.value = grp
                    _isLoading.value = false
                    
                    // Recalculate permissions on group update
                    val myId = _currentUserId.value
                    val amICreator = sameGroupUserId(grp.creator_id, myId)
                    val amIGroupAdmin = _members.value.find { sameGroupUserId(it.user_id, myId) }
                        ?.role
                        .isGroupAdminRole()
                    _isAdmin.value = amICreator || amIGroupAdmin
                }
            }
        }

        // 3. Observe Members Reactively
        viewModelScope.launch {
            // Initial Load with names
            loadGroupMembers()
            
            groupRepository.getMembersFlow(id).collect { basicMembers ->
                if (basicMembers.isNotEmpty()) {
                    val currentMap = _members.value.associateBy { it.user_id }
                    
                    val enriched = basicMembers.map { basic ->
                        val cached = currentMap[basic.user_id]
                        if (cached != null) {
                            cached.copy(role = basic.role)
                        } else {
                            basic
                        }
                    }
                    
                    // If we have nameless members, force a reload
                    if (enriched.any { it.userName == null && it.user_id != _currentUserId.value }) {
                        loadGroupMembers()
                    } else {
                        _members.value = enriched
                    }
                    
                    // Recalculate permissions on member update
                    val myId = _currentUserId.value
                    val grp = _group.value
                    val amICreator = sameGroupUserId(grp?.creator_id, myId)
                    val amIGroupAdmin = enriched.find { sameGroupUserId(it.user_id, myId) }
                        ?.role
                        .isGroupAdminRole()
                    _isAdmin.value = amICreator || amIGroupAdmin
                }
            }
        }
    }

    fun leaveGroup(onSuccess: () -> Unit) {
        val id = groupId ?: return
        viewModelScope.launch {
            val result = groupRepository.leaveGroup(id)
            if (result.isSuccess) {
                onSuccess()
            }
        }
    }

    fun deleteGroup(onSuccess: () -> Unit) {
        val id = groupId ?: return
        viewModelScope.launch {
            val result = groupRepository.deleteGroup(id)
            if (result.isSuccess) {
                onSuccess()
            }
        }
    }

    fun deleteGroupLocally(onSuccess: () -> Unit) {
        val id = groupId ?: return
        viewModelScope.launch {
            groupRepository.deleteGroupLocally(id)
            onSuccess()
        }
    }

    fun removeMember(userId: String) {
        val id = groupId ?: return
        viewModelScope.launch {
            groupRepository.removeMember(id, userId)
            // Reload members to reflect changes (or rely on realtime if enabled/working)
            // For now, let's force a reload of members to be safe
            loadGroupMembers()
        }
    }

    fun addMember(userId: String, onSuccess: () -> Unit) {
        val id = groupId ?: return
        viewModelScope.launch {
            val result = groupRepository.addMember(id, userId)
            if (result.isSuccess) {
                onSuccess()
                loadGroupMembers()
            }
        }
    }

    // Search users for adding members
    private val _searchResults = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    fun searchUsers(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            val result = userRepository.searchUsers(query)
            if (result.isSuccess) {
                // Filter out existing members
                val currentMemberIds = _members.value.map { it.user_id }.toSet()
                _searchResults.value = result.getOrDefault(emptyList()).filter { 
                    it.id !in currentMemberIds 
                }
            } else {
                _searchResults.value = emptyList()
            }
        }
    }
    
    fun promoteToAdmin(userId: String) {
        val id = groupId ?: return
        viewModelScope.launch {
            groupRepository.updateMemberRole(id, userId, "admin")
            loadGroupMembers()
        }
    }



    fun demoteToMember(userId: String) {
        val id = groupId ?: return
        viewModelScope.launch {
            groupRepository.updateMemberRole(id, userId, "member")
            loadGroupMembers()
        }
    }

    fun updateGroupInfo(name: String, description: String?, imageBytes: ByteArray?, onlyAdminsCanTalk: Boolean, onSuccess: () -> Unit) {
        val id = groupId ?: return
        viewModelScope.launch {
            val result = groupRepository.updateGroupInfo(id, name, description, imageBytes, onlyAdminsCanTalk)
            if (result.isSuccess) {
                onSuccess()
            }
        }
    }

    private fun loadGroupMembers() {
         val id = groupId ?: return
         viewModelScope.launch {
             val membersResult = groupRepository.getGroupMembers(id)
             if (membersResult.isSuccess) {
                _members.value = membersResult.getOrDefault(emptyList())
            }
         }
    }
}

private fun sameGroupUserId(left: String?, right: String?): Boolean {
    val cleanLeft = left?.trim()?.lowercase().orEmpty()
    val cleanRight = right?.trim()?.lowercase().orEmpty()
    return cleanLeft.isNotBlank() && cleanLeft == cleanRight
}

private fun String?.isGroupAdminRole(): Boolean =
    this?.trim()?.lowercase() in setOf("admin", "owner", "creator")
