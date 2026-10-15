package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Community
import conectate.com.data.model.CommunityEvent
import conectate.com.data.model.CommunityMember
import conectate.com.data.model.CommunityMemberProfile
import conectate.com.data.model.CommunityRoleCatalog
import conectate.com.data.repository.CommunityRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CommunityDetailViewModel @Inject constructor(
    private val communityRepository: CommunityRepository,
    private val authService: AuthService,
    private val userRepository: conectate.com.data.repository.UserRepository,
    private val membershipRepository: conectate.com.data.repository.CollectionMembershipRepository
) : ViewModel() {

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage = _actionMessage.asStateFlow()

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    private val _community = MutableStateFlow<Community?>(null)
    val community = _community.asStateFlow()

    private val _members = MutableStateFlow<List<CommunityMember>>(emptyList())
    val members = _members.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    val currentUserId = _currentUserId.asStateFlow()

    private val _isMember = MutableStateFlow(false)
    val isMember = _isMember.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin = _isAdmin.asStateFlow()

    private val _isCreator = MutableStateFlow(false)
    val isCreator = _isCreator.asStateFlow()

    private val _roleCatalog = MutableStateFlow<CommunityRoleCatalog?>(null)
    val roleCatalog = _roleCatalog.asStateFlow()

    private val _communityProfile = MutableStateFlow<CommunityMemberProfile?>(null)
    val communityProfile = _communityProfile.asStateFlow()

    private val _communityProfiles = MutableStateFlow<Map<String, CommunityMemberProfile>>(emptyMap())
    val communityProfiles = _communityProfiles.asStateFlow()

    private val _events = MutableStateFlow<List<CommunityEvent>>(emptyList())
    val events = _events.asStateFlow()

    private val _canManageEvents = MutableStateFlow(false)
    val canManageEvents = _canManageEvents.asStateFlow()

    private val _membershipPolicy = MutableStateFlow<conectate.com.data.localfallback.CollectionMembershipPolicy?>(null)
    val membershipPolicy = _membershipPolicy.asStateFlow()
    private val _membershipBusy = MutableStateFlow(false)
    val membershipBusy = _membershipBusy.asStateFlow()
    private val _membershipError = MutableStateFlow<String?>(null)
    val membershipError = _membershipError.asStateFlow()

    private var membershipPolicyRequest = 0L

    fun refreshMembershipPolicy() {
        val id = communityId ?: return
        val account = authService.getCurrentUserId()
        val request = ++membershipPolicyRequest
        viewModelScope.launch {
            val result = membershipRepository.policy("communities", id)
            if (request != membershipPolicyRequest || communityId != id || authService.getCurrentUserId() != account) return@launch
            _membershipPolicy.value = result.getOrNull()
        }
    }

    fun setMembersCanAdd(enabled: Boolean) {
        val id = communityId ?: return
        if (_membershipBusy.value || _membershipPolicy.value?.canManagePolicy != true) return
        ++membershipPolicyRequest
        _membershipBusy.value = true
        val account = authService.getCurrentUserId()
        viewModelScope.launch {
            try {
                val result = membershipRepository.setMembersCanAdd("communities", id, enabled)
                if (communityId != id || authService.getCurrentUserId() != account) return@launch
                result.onSuccess { ++membershipPolicyRequest; _membershipPolicy.value = it }.onFailure {
                    _actionMessage.value = "No se pudo guardar el permiso. Inténtalo de nuevo."
                }
            } finally { _membershipBusy.value = false }
        }
    }

    fun addMember(userId: String, from: String?, until: String?, onSuccess: () -> Unit) {
        val id = communityId ?: return
        if (_membershipBusy.value || _membershipPolicy.value?.canAddMembers != true) return
        _membershipBusy.value = true
        _membershipError.value = null
        val account = authService.getCurrentUserId()
        viewModelScope.launch {
            try {
                val result = membershipRepository.add("communities", id, userId, from, until)
                if (communityId != id || authService.getCurrentUserId() != account) return@launch
                if (result.isSuccess) { onSuccess(); loadMembers() }
                else {
                    _membershipError.value = "No se pudo añadir. Revisa el permiso y el intervalo de historial e inténtalo de nuevo."
                    refreshMembershipPolicy()
                }
            } finally { _membershipBusy.value = false }
        }
    }

    private var communityId: String? = null

    init {
        val userId = authService.getCurrentUserId()
        _currentUserId.value = userId
    }

    fun loadCommunityDetails(id: String) {
        communityId = id
        _membershipPolicy.value = null
        refreshMembershipPolicy()
        _isLoading.value = true
        _community.value = null
        _members.value = emptyList()
        _isMember.value = false
        _isAdmin.value = false
        _isCreator.value = false
        _roleCatalog.value = null
        _communityProfile.value = null
        _communityProfiles.value = emptyMap()
        _events.value = emptyList()
        _canManageEvents.value = false
        
        // 1. Force Sync
        viewModelScope.launch {
             try {
                 communityRepository.syncCommunities()
             } catch (e: Exception) {
                 if (e is kotlinx.coroutines.CancellationException) throw e
                 android.util.Log.w("CommunityDetailViewModel", "Could not refresh community details", e)
             }
        }

        // 2. Observe Community
        viewModelScope.launch {
            communityRepository.getCommunityFlow(id).collect { comm ->
                _community.value = comm
                if (comm != null) {
                    _isLoading.value = false
                    recomputePermissions()
                }
            }
        }
        
        // 3. Observe Membership Status
        viewModelScope.launch {
            val userId = _currentUserId.value ?: authService.getCurrentUserId()
            if (userId != null) {
                communityRepository.isUserMemberFlow(id, userId).collect { isMem ->
                    _isMember.value = isMem
                    if (isMem) refreshCommunityFeatures(silent = true)
                }
            }
        }

        // 4. Observe Members Reactively
        viewModelScope.launch {
            // Initial Load with names
            loadMembers()
            
            communityRepository.getMembersFlow(id).collect { basicMembers ->
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
                    loadMembers()
                } else {
                    _members.value = enriched
                    recomputePermissions()
                }
            }
        }
    }

    private val _searchResults = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val searchResults = _searchResults.asStateFlow()
    fun searchUsers(query: String) {
        if (query.trim().length < 3) { _searchResults.value = emptyList(); return }
        viewModelScope.launch {
            val ids = _members.value.map { it.user_id }.toSet()
            _searchResults.value = userRepository.searchUsers(query).getOrDefault(emptyList()).filter { it.id !in ids }
        }
    }

    fun joinCommunity() {
        communityId?.let {
            viewModelScope.launch {
                communityRepository.joinCommunity(it)
                loadCommunityDetails(it)
            }
        }
    }

    fun leaveCommunity(onSuccess: () -> Unit) {
        val id = communityId ?: return
        viewModelScope.launch {
            val result = communityRepository.leaveCommunity(id)
            if (result.isSuccess) {
                onSuccess()
            }
        }
    }

    fun deleteCommunity(onSuccess: () -> Unit) {
        val id = communityId ?: return
        viewModelScope.launch {
            val result = communityRepository.deleteCommunity(id)
            if (result.isSuccess) {
                onSuccess()
            }
        }
    }

    fun deleteCommunityLocally(onSuccess: () -> Unit) {
        val id = communityId ?: return
        viewModelScope.launch {
            communityRepository.deleteCommunityLocally(id)
            onSuccess()
        }
    }

    fun setMemberRole(userId: String, role: String) {
        val id = communityId ?: return
        viewModelScope.launch {
            val result = communityRepository.updateMemberRole(id, userId, role)
            if (result.isSuccess) {
                loadMembers()
            } else {
                _actionMessage.value = result.exceptionOrNull()?.message
                    ?: "No se pudo actualizar el rol."
            }
        }
    }

    fun loadRoleCatalog() {
        val id = communityId ?: return
        val myId = _currentUserId.value
        val currentRole = if (_isCreator.value) {
            "owner"
        } else {
            _members.value.firstOrNull { sameCommunityUserId(it.user_id, myId) }?.role
        }
        viewModelScope.launch {
            val result = communityRepository.getCommunityRoleCatalog(id, currentRole)
            if (result.isSuccess) {
                _roleCatalog.value = result.getOrNull()
            } else {
                _actionMessage.value = result.exceptionOrNull()?.message
                    ?: "No se pudo cargar la vista de permisos."
            }
        }
    }

    fun removeMember(userId: String) {
        val id = communityId ?: return
        viewModelScope.launch {
            communityRepository.removeMember(id, userId)
            loadMembers()
        }
    }

    fun saveCommunityProfile(
        alias: String,
        bio: String,
        distinctive: String,
        onSuccess: () -> Unit
    ) {
        val id = communityId ?: return
        viewModelScope.launch {
            val result = communityRepository.updateCommunityMemberProfile(
                communityId = id,
                alias = alias,
                bio = bio,
                distinctive = distinctive
            )
            if (result.isSuccess) {
                val profile = result.getOrThrow()
                _communityProfile.value = profile
                _communityProfiles.value = _communityProfiles.value + (
                    profile.userId.trim().lowercase() to profile
                )
                _actionMessage.value = "Tu perfil de la comunidad se guardó."
                onSuccess()
            } else {
                _actionMessage.value = result.exceptionOrNull()?.message
                    ?: "No se pudo guardar tu perfil de la comunidad."
            }
        }
    }

    fun createCommunityEvent(
        title: String,
        description: String,
        startsAt: String,
        location: String,
        onSuccess: () -> Unit
    ) {
        val id = communityId ?: return
        viewModelScope.launch {
            val result = communityRepository.createCommunityEvent(
                communityId = id,
                title = title,
                description = description,
                startsAt = startsAt,
                endsAt = null,
                location = location
            )
            if (result.isSuccess) {
                val event = result.getOrThrow()
                _events.value = (_events.value + event)
                    .distinctBy { it.id }
                    .sortedBy { it.startsAt }
                _actionMessage.value = "Evento creado."
                onSuccess()
            } else {
                _actionMessage.value = result.exceptionOrNull()?.message
                    ?: "No se pudo crear el evento."
            }
        }
    }

    fun setEventAttendance(
        event: CommunityEvent,
        attending: Boolean,
        reminderMinutes: Int?
    ) {
        val id = communityId ?: return
        viewModelScope.launch {
            val result = communityRepository.updateCommunityEventAttendance(
                communityId = id,
                eventId = event.id,
                attending = attending,
                reminderMinutes = reminderMinutes
            )
            if (result.isSuccess) {
                replaceEvent(result.getOrThrow())
                _actionMessage.value = when {
                    !attending -> "Ya no asistirás a este evento."
                    reminderMinutes != null -> "Asistencia y recordatorio guardados."
                    else -> "Tu asistencia quedó confirmada."
                }
            } else {
                _actionMessage.value = result.exceptionOrNull()?.message
                    ?: "No se pudo actualizar tu asistencia."
            }
        }
    }

    fun cancelCommunityEvent(event: CommunityEvent) {
        val id = communityId ?: return
        viewModelScope.launch {
            val result = communityRepository.cancelCommunityEvent(id, event.id)
            if (result.isSuccess) {
                replaceEvent(result.getOrThrow())
                _actionMessage.value = "Evento cancelado."
            } else {
                _actionMessage.value = result.exceptionOrNull()?.message
                    ?: "No se pudo cancelar el evento."
            }
        }
    }

    fun updateCommunityInfo(
        name: String,
        description: String?,
        imageBytes: ByteArray?,
        isPublic: Boolean,
        onlyAdminsCanTalk: Boolean,
        dailyMessageLimit: Int,
        onSuccess: () -> Unit
    ) {
        val id = communityId ?: return
        viewModelScope.launch {
            val result = communityRepository.updateCommunityInfo(
                communityId = id,
                name = name,
                description = description,
                imageBytes = imageBytes,
                isPublic = isPublic,
                onlyAdminsCanTalk = onlyAdminsCanTalk,
                dailyMessageLimit = dailyMessageLimit
            )

            if (result.isSuccess) {
                _community.value = _community.value?.copy(
                    name = name.trim(),
                    description = description?.trim().orEmpty().ifBlank { null },
                    is_public = isPublic,
                    only_admins_can_talk = onlyAdminsCanTalk,
                    daily_message_limit = dailyMessageLimit.coerceIn(0, 1000)
                )
                onSuccess()
            } else {
                val msg = result.exceptionOrNull()?.message ?: "No se pudo guardar los cambios"
                android.util.Log.e("CommunityDetailVM", "updateCommunityInfo failed. id=$id msg=$msg", result.exceptionOrNull())
                _actionMessage.value = msg
            }
        }
    }

    private fun loadMembers() {
        val id = communityId ?: return
        viewModelScope.launch {
            val membersResult = communityRepository.getCommunityMembers(id)
            if (membersResult.isSuccess) {
                _members.value = membersResult.getOrDefault(emptyList())
                recomputePermissions()
                val myId = _currentUserId.value
                val isIncluded = _members.value.any { sameCommunityUserId(it.user_id, myId) }
                if (isIncluded || sameCommunityUserId(_community.value?.creator_id, myId)) {
                    refreshCommunityFeatures(silent = true)
                }
            }
        }
    }

    private fun refreshCommunityFeatures(silent: Boolean) {
        val id = communityId ?: return
        viewModelScope.launch {
            val profileResult = communityRepository.getCommunityMemberProfiles(id)
            if (profileResult.isSuccess) {
                val catalog = profileResult.getOrThrow()
                _communityProfile.value = catalog.profile
                _communityProfiles.value = catalog.profiles.associateBy {
                    it.userId.trim().lowercase()
                }
            } else if (!silent) {
                _actionMessage.value = profileResult.exceptionOrNull()?.message
                    ?: "No se pudo cargar tu perfil de la comunidad."
            }
        }
        viewModelScope.launch {
            val eventsResult = communityRepository.getCommunityEvents(id)
            if (eventsResult.isSuccess) {
                val catalog = eventsResult.getOrThrow()
                _events.value = catalog.events
                _canManageEvents.value = catalog.canManageEvents
            } else if (!silent) {
                _actionMessage.value = eventsResult.exceptionOrNull()?.message
                    ?: "No se pudieron cargar los eventos."
            }
        }
    }

    private fun replaceEvent(event: CommunityEvent) {
        _events.value = _events.value
            .map { if (it.id == event.id) event else it }
            .let { current ->
                if (current.none { it.id == event.id }) current + event else current
            }
            .sortedBy { it.startsAt }
    }

    private fun recomputePermissions() {
        val myId = _currentUserId.value
        val comm = _community.value
        val amICreator = sameCommunityUserId(comm?.creator_id, myId)
        val amIAdmin = _members.value.find { sameCommunityUserId(it.user_id, myId) }
            ?.role
            .isCommunityAdminRole()
        _isCreator.value = amICreator
        _isAdmin.value = amICreator || amIAdmin
    }
}

private fun sameCommunityUserId(left: String?, right: String?): Boolean {
    val cleanLeft = left?.trim()?.lowercase().orEmpty()
    val cleanRight = right?.trim()?.lowercase().orEmpty()
    return cleanLeft.isNotBlank() && cleanLeft == cleanRight
}

private fun String?.isCommunityAdminRole(): Boolean =
    this?.trim()?.lowercase() in setOf("admin", "owner", "creator")
