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
    private val userRepository: conectate.com.data.repository.UserRepository,
    private val membershipRepository: conectate.com.data.repository.CollectionMembershipRepository
) : ViewModel() {

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage = _actionMessage.asStateFlow()

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

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

    private val _isCreator = MutableStateFlow(false)
    val isCreator = _isCreator.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted = _isDeleted.asStateFlow()

    private val _membershipPolicy = MutableStateFlow<conectate.com.data.localfallback.CollectionMembershipPolicy?>(null)
    val membershipPolicy = _membershipPolicy.asStateFlow()
    private val _membershipBusy = MutableStateFlow(false)
    val membershipBusy = _membershipBusy.asStateFlow()
    private val _membershipError = MutableStateFlow<String?>(null)
    val membershipError = _membershipError.asStateFlow()

    private var membershipPolicyRequest = 0L

    fun refreshMembershipPolicy() {
        val id = groupId ?: return
        val account = authService.getCurrentUserId()
        val request = ++membershipPolicyRequest
        viewModelScope.launch {
            val result = membershipRepository.policy("groups", id)
            if (request != membershipPolicyRequest || groupId != id || authService.getCurrentUserId() != account) return@launch
            _membershipPolicy.value = result.getOrNull()
        }
    }

    fun setMembersCanAdd(enabled: Boolean) {
        val id = groupId ?: return
        if (_membershipBusy.value || _membershipPolicy.value?.canManagePolicy != true) return
        ++membershipPolicyRequest
        _membershipBusy.value = true
        val account = authService.getCurrentUserId()
        viewModelScope.launch {
            try {
                val result = membershipRepository.setMembersCanAdd("groups", id, enabled)
                if (groupId != id || authService.getCurrentUserId() != account) return@launch
                result.onSuccess { ++membershipPolicyRequest; _membershipPolicy.value = it }.onFailure {
                    _actionMessage.value = "No se pudo guardar el permiso. Inténtalo de nuevo."
                }
            } finally { _membershipBusy.value = false }
        }
    }

    fun addMember(userId: String, from: String?, until: String?, onSuccess: () -> Unit) {
        val id = groupId ?: return
        if (_membershipBusy.value || _membershipPolicy.value?.canAddMembers != true) return
        _membershipBusy.value = true
        _membershipError.value = null
        val account = authService.getCurrentUserId()
        viewModelScope.launch {
            try {
                val result = membershipRepository.add("groups", id, userId, from, until)
                if (groupId != id || authService.getCurrentUserId() != account) return@launch
                if (result.isSuccess) { onSuccess(); loadGroupMembers() }
                else {
                    _membershipError.value = "No se pudo añadir. Revisa el permiso y el intervalo de historial e inténtalo de nuevo."
                    refreshMembershipPolicy()
                }
            } finally { _membershipBusy.value = false }
        }
    }

    private var groupId: String? = null
    private var redeemedInviteToken: String? = null

    init {
        val userId = authService.getCurrentUserId()
        _currentUserId.value = userId
    }

    fun loadGroupDetails(id: String, inviteToken: String? = null) {
        groupId = id
        _membershipPolicy.value = null
        // An invitation must finish before requesting the new member's rights.
        if (inviteToken.isNullOrBlank()) refreshMembershipPolicy()
        _isLoading.value = true
        _group.value = null
        _members.value = emptyList()
        _isAdmin.value = false
        _isCreator.value = false
        _isDeleted.value = false
        
        // 1. Force Sync (Fire and forget)
        viewModelScope.launch {
             val cleanInvite = inviteToken?.trim().orEmpty()
             if (cleanInvite.isNotBlank() && redeemedInviteToken != cleanInvite) {
                 val joined = groupRepository.joinGroupWithInvite(id, cleanInvite)
                 if (joined.isSuccess) {
                     redeemedInviteToken = cleanInvite
                     _actionMessage.value = "Te uniste al grupo"
                 } else {
                     _actionMessage.value = joined.exceptionOrNull()?.message ?: "La invitación no es válida"
                 }
             }
             if (cleanInvite.isNotBlank()) refreshMembershipPolicy()
             try {
                 groupRepository.syncGroups()
             } catch (e: Exception) {
                 if (e is kotlinx.coroutines.CancellationException) throw e
                 android.util.Log.w("GroupDetailViewModel", "Could not refresh group details", e)
             }
        }

        // 2. Observe Group
        viewModelScope.launch {
            groupRepository.getGroupFlow(id).collect { grp ->
                if (grp != null) {
                    if (grp.is_deleted) _isDeleted.value = true
                    _group.value = grp
                    _isLoading.value = false
                    recomputePermissions()
                }
            }
        }

        // 3. Observe Members Reactively
        viewModelScope.launch {
            // Initial Load with names
            loadGroupMembers()
            
            groupRepository.getMembersFlow(id).collect { basicMembers ->
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
                    recomputePermissions()
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
                _group.value = _group.value?.copy(
                    name = name.trim(),
                    description = description?.trim().orEmpty().ifBlank { null },
                    only_admins_can_talk = onlyAdminsCanTalk
                )
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
                recomputePermissions()
            }
         }
    }

    fun createShareLink(onReady: (String) -> Unit) {
        val id = groupId ?: return
        viewModelScope.launch {
            groupRepository.createGroupInvite(id).fold(
                onSuccess = { token ->
                      onReady("https://red-social-android.vercel.app/group/$id?invite=${android.net.Uri.encode(token)}")
                },
                onFailure = { error ->
                    _actionMessage.value = error.message ?: "No se pudo crear el enlace"
                }
            )
        }
    }

    fun setRestDuration(hours: Long?) {
        val id = groupId ?: return
        viewModelScope.launch {
            val until = hours?.let { java.time.Instant.now().plusSeconds(it * 3600L).toString() }
            val result = groupRepository.setGroupRestUntil(id, until)
            if (result.isSuccess) {
                _group.value = _group.value?.copy(rest_until = until)
                _actionMessage.value = if (until == null) "Modo descanso desactivado" else "Modo descanso activado"
            } else {
                _actionMessage.value = result.exceptionOrNull()?.message ?: "No se pudo cambiar el modo descanso"
            }
        }
    }

    private fun recomputePermissions() {
        val myId = _currentUserId.value
        val grp = _group.value
        val amICreator = sameGroupUserId(grp?.creator_id, myId)
        val amIGroupAdmin = _members.value.find { sameGroupUserId(it.user_id, myId) }
            ?.role
            .isGroupAdminRole()
        _isCreator.value = amICreator
        _isAdmin.value = amICreator || amIGroupAdmin
    }
}

private fun sameGroupUserId(left: String?, right: String?): Boolean {
    val cleanLeft = left?.trim()?.lowercase().orEmpty()
    val cleanRight = right?.trim()?.lowercase().orEmpty()
    return cleanLeft.isNotBlank() && cleanLeft == cleanRight
}

private fun String?.isGroupAdminRole(): Boolean =
    this?.trim()?.lowercase() in setOf("admin", "owner", "creator")
