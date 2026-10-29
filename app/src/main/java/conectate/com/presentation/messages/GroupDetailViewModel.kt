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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    private val actions = CollectionActionRunner(viewModelScope, authService::getCurrentUserId, { _actionMessage.value = it }, authService.currentSession.map { authService.getCurrentUserId() })
    val actionsBusy = actions.busy

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    private val _group = MutableStateFlow<Group?>(null)
    val group = _group.asStateFlow()

    private val _members = MutableStateFlow<List<GroupMember>>(emptyList())
    val members = _members.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError = _loadError.asStateFlow()

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
    private var pendingInviteToken: String? = null
    private val detailSession = CommunityDetailSession(viewModelScope)
    private var membersLoadJob: kotlinx.coroutines.Job? = null

    init {
        val userId = authService.getCurrentUserId()
        _currentUserId.value = userId
    }

    fun retryGroupDetails() { groupId?.let { loadGroupDetails(it, pendingInviteToken, force = true) } }

    fun loadGroupDetails(id: String, inviteToken: String? = null, force: Boolean = false) {
        val ticket = detailSession.open(id, authService.getCurrentUserId(), force || !inviteToken.isNullOrBlank()) ?: return
        groupId = id
        pendingInviteToken = inviteToken?.trim()?.takeIf(String::isNotBlank)
        _currentUserId.value = ticket.accountId
        membersLoadJob = null
        _loadError.value = null
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
        ticket.scope.launch {
             val cleanInvite = inviteToken?.trim().orEmpty()
             if (cleanInvite.isNotBlank() && redeemedInviteToken != cleanInvite) {
                 val joined = groupRepository.joinGroupWithInvite(id, cleanInvite)
                 if (!detailSession.isCurrent(ticket, authService.getCurrentUserId())) return@launch
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
        ticket.scope.launch {
            val initial = conectate.com.core.network.runReadWithDeadline(12_000) {
                groupRepository.getGroupFlow(id).filterNotNull().first()
            }
            if (detailSession.isCurrent(ticket, authService.getCurrentUserId()) && initial.isFailure) {
                _loadError.value = "No se pudo cargar el grupo. Comprueba el acceso y la conexión y reintenta."
                _isLoading.value = false
            }
        }
        ticket.scope.launch {
            groupRepository.getGroupFlow(id).collect { grp ->
                if (!detailSession.isCurrent(ticket, authService.getCurrentUserId())) return@collect
                if (grp != null) {
                    _loadError.value = null
                    if (grp.is_deleted) _isDeleted.value = true
                    _group.value = grp
                    _isLoading.value = false
                    recomputePermissions()
                }
            }
        }

        // 3. Observe Members Reactively
        ticket.scope.launch {
            // Initial Load with names
            loadGroupMembers()
            
            groupRepository.getMembersFlow(id).collect { basicMembers ->
                if (!detailSession.isCurrent(ticket, authService.getCurrentUserId())) return@collect
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
        actions.run(id, "No se pudo salir del grupo. Revisa la conexión y reintenta.", { groupId == id },
            { groupRepository.leaveGroup(id) }, { onSuccess() })
    }

    fun deleteGroup(onSuccess: () -> Unit) {
        val id = groupId ?: return
        actions.run(id, "No se pudo eliminar el grupo. Comprueba tus permisos y reintenta.", { groupId == id },
            { groupRepository.deleteGroup(id) }, { onSuccess() })
    }

    fun deleteGroupLocally(onSuccess: () -> Unit) {
        val id = groupId ?: return
        actions.run(id, "No se pudo quitar el grupo de este dispositivo.", { groupId == id },
            { groupRepository.deleteGroupLocally(id); Result.success(Unit) }, { onSuccess() })
    }

    fun removeMember(userId: String) {
        val id = groupId ?: return
        actions.run(id, "No se pudo quitar al participante. Comprueba tus permisos y reintenta.", { groupId == id },
            { groupRepository.removeMember(id, userId) }, { loadGroupMembers() })
    }

    // Search users for adding members
    private val _searchResults = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val searchResults = _searchResults.asStateFlow()
    private var searchJob: kotlinx.coroutines.Job? = null
    private var searchRevision = 0L

    fun searchUsers(query: String) {
        searchJob?.cancel()
        val revision = ++searchRevision
        val account = authService.getCurrentUserId()
        val id = groupId
        _searchResults.value = emptyList()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            val result = conectate.com.core.network.runReadWithDeadline(10_000) { userRepository.searchUsers(query).getOrThrow() }
            if (revision != searchRevision || account != authService.getCurrentUserId() || id != groupId) return@launch
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
        actions.run(id, "No se pudo cambiar el rol. Comprueba tus permisos y reintenta.", { groupId == id },
            { groupRepository.updateMemberRole(id, userId, "admin") }, { loadGroupMembers() })
    }



    fun demoteToMember(userId: String) {
        val id = groupId ?: return
        actions.run(id, "No se pudo cambiar el rol. Comprueba tus permisos y reintenta.", { groupId == id },
            { groupRepository.updateMemberRole(id, userId, "member") }, { loadGroupMembers() })
    }

    fun updateGroupInfo(name: String, description: String?, imageBytes: ByteArray?, onlyAdminsCanTalk: Boolean, onSuccess: () -> Unit) {
        val id = groupId ?: return
        actions.run(id, "No se pudo guardar la información del grupo. Reintenta.", { groupId == id },
            { groupRepository.updateGroupInfo(id, name, description, imageBytes, onlyAdminsCanTalk) }, {
                _group.value = _group.value?.copy(
                    name = name.trim(),
                    description = description?.trim().orEmpty().ifBlank { null },
                    only_admins_can_talk = onlyAdminsCanTalk
                )
                onSuccess()
        })
    }

    private fun loadGroupMembers() {
         val ticket = detailSession.current() ?: return
         val id = ticket.communityId
         if (membersLoadJob?.isActive == true) return
         membersLoadJob = ticket.scope.launch {
             val membersResult = conectate.com.core.network.runReadWithDeadline(10_000) { groupRepository.getGroupMembers(id).getOrThrow() }
             if (!detailSession.isCurrent(ticket, authService.getCurrentUserId())) return@launch
             if (membersResult.isSuccess) {
                _members.value = membersResult.getOrDefault(emptyList())
                recomputePermissions()
            }
         }
    }

    fun createShareLink(onReady: (String) -> Unit) {
        val id = groupId ?: return
        actions.run("invite:$id", "No se pudo crear el enlace. Comprueba tus permisos y reintenta.", { groupId == id },
            { groupRepository.createGroupInvite(id) }, { token ->
                onReady("https://red-social-android.vercel.app/group/$id?invite=${android.net.Uri.encode(token)}")
            })
    }

    fun setRestDuration(hours: Long?) {
        val id = groupId ?: return
        val until = hours?.let { java.time.Instant.now().plusSeconds(it.coerceIn(0, 8760) * 3600L).toString() }
        actions.run(id, "No se pudo cambiar el modo descanso. Reintenta.", { groupId == id },
            { groupRepository.setGroupRestUntil(id, until) }, {
                _group.value = _group.value?.copy(rest_until = until)
                _actionMessage.value = if (until == null) "Modo descanso desactivado" else "Modo descanso activado"
            })
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
