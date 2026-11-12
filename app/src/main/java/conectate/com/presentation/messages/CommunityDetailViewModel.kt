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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
    private val actions = CollectionActionRunner(viewModelScope, authService::getCurrentUserId, { _actionMessage.value = it }, authService.currentSession.map { authService.getCurrentUserId() })
    val actionsBusy = actions.busy

    fun consumeActionMessage() {
        _actionMessage.value = null
    }

    fun createShareLink(onReady: (String) -> Unit) {
        val id = communityId ?: return
        val account = authService.getCurrentUserId()
        viewModelScope.launch {
            communityRepository.createCommunityInvite(id).fold(
                onSuccess = { token -> if (communityId == id && authService.getCurrentUserId() == account)
                    onReady("https://novvinapp.com/community/$id?invite=${android.net.Uri.encode(token)}") },
                onFailure = { _actionMessage.value = "No se pudo crear la invitación. Comprueba los permisos de la comunidad." }
            )
        }
    }

    private val _community = MutableStateFlow<Community?>(null)
    val community = _community.asStateFlow()

    private val _members = MutableStateFlow<List<CommunityMember>>(emptyList())
    val members = _members.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError = _loadError.asStateFlow()

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
    private val detailSession = CommunityDetailSession(viewModelScope)
    private var membersLoadJob: Job? = null
    private var featuresLoadJob: Job? = null

    init {
        val userId = authService.getCurrentUserId()
        _currentUserId.value = userId
    }

    fun retryCommunityDetails() { communityId?.let { loadCommunityDetails(it, force = true) } }

    fun loadCommunityDetails(id: String, force: Boolean = false) {
        val ticket = detailSession.open(id, authService.getCurrentUserId(), force) ?: return
        membersLoadJob = null
        featuresLoadJob = null
        communityId = id
        _currentUserId.value = ticket.accountId
        _membershipPolicy.value = null
        refreshMembershipPolicy()
        _isLoading.value = true
        _loadError.value = null
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
        ticket.scope.launch {
             try {
                 communityRepository.syncCommunities()
             } catch (e: Exception) {
                 if (e is kotlinx.coroutines.CancellationException) throw e
                 android.util.Log.w("CommunityDetailViewModel", "Could not refresh community details", e)
             }
        }

        // 2. Observe Community
        ticket.scope.launch {
            val initial = conectate.com.core.network.runReadWithDeadline(12_000) {
                communityRepository.getCommunityFlow(id).filterNotNull().first()
            }
            if (detailSession.isCurrent(ticket, authService.getCurrentUserId()) && initial.isFailure) {
                _loadError.value = "No se pudo cargar la comunidad. Comprueba el acceso y la conexión y reintenta."
                _isLoading.value = false
            }
        }
        ticket.scope.launch {
            communityRepository.getCommunityFlow(id).collect { comm ->
                if (!detailSession.isCurrent(ticket, authService.getCurrentUserId())) return@collect
                _community.value = comm
                if (comm != null) {
                    _loadError.value = null
                    _isLoading.value = false
                    recomputePermissions()
                }
            }
        }
        
        // 3. Observe Membership Status
        ticket.scope.launch {
            val userId = _currentUserId.value ?: authService.getCurrentUserId()
            if (userId != null) {
                communityRepository.isUserMemberFlow(id, userId).distinctUntilChanged().collect { isMem ->
                    if (!detailSession.isCurrent(ticket, authService.getCurrentUserId())) return@collect
                    _isMember.value = isMem
                    if (isMem) refreshCommunityFeatures(silent = true)
                }
            }
        }

        // 4. Observe Members Reactively
        ticket.scope.launch {
            // Initial Load with names
            loadMembers()
            
            communityRepository.getMembersFlow(id).collect { basicMembers ->
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
    private var searchJob: Job? = null
    private var searchRevision = 0L
    fun searchUsers(query: String) {
        searchJob?.cancel()
        val revision = ++searchRevision
        val account = authService.getCurrentUserId()
        val id = communityId
        _searchResults.value = emptyList()
        if (query.trim().length < 3) { _searchResults.value = emptyList(); return }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(300)
            val ids = _members.value.map { it.user_id }.toSet()
            val result = conectate.com.core.network.runReadWithDeadline(10_000) { userRepository.searchUsers(query).getOrThrow() }
            if (revision != searchRevision || account != authService.getCurrentUserId() || id != communityId) return@launch
            _searchResults.value = result.getOrDefault(emptyList()).filter { it.id !in ids }
        }
    }

    fun joinCommunity() {
        val id = communityId ?: return
        actions.run(id, "No se pudo entrar a la comunidad. Revisa el acceso y reintenta.", { communityId == id },
            { communityRepository.joinCommunity(id) }, { retryCommunityDetails() })
    }

    fun leaveCommunity(onSuccess: () -> Unit) {
        val id = communityId ?: return
        actions.run(id, "No se pudo salir de la comunidad. Revisa la conexión y reintenta.", { communityId == id },
            { communityRepository.leaveCommunity(id) }, { onSuccess() })
    }

    fun deleteCommunity(onSuccess: () -> Unit) {
        val id = communityId ?: return
        actions.run(id, "No se pudo eliminar la comunidad. Comprueba tus permisos y reintenta.", { communityId == id },
            { communityRepository.deleteCommunity(id) }, { onSuccess() })
    }

    fun deleteCommunityLocally(onSuccess: () -> Unit) {
        val id = communityId ?: return
        actions.run(id, "No se pudo quitar la comunidad de este dispositivo.", { communityId == id },
            { communityRepository.deleteCommunityLocally(id); Result.success(Unit) }, { onSuccess() })
    }

    fun setMemberRole(userId: String, role: String) {
        val id = communityId ?: return
        actions.run(id, "No se pudo actualizar el rol. Comprueba tus permisos y reintenta.", { communityId == id },
            { communityRepository.updateMemberRole(id, userId, role) }, { loadMembers() })
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
            val owner = authService.getCurrentUserId()
            val result = conectate.com.core.network.runReadWithDeadline(10_000) { communityRepository.getCommunityRoleCatalog(id, currentRole).getOrThrow() }
            if (communityId != id || owner != authService.getCurrentUserId()) return@launch
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
        actions.run(id, "No se pudo quitar al participante. Comprueba tus permisos y reintenta.", { communityId == id },
            { communityRepository.removeMember(id, userId) }, { loadMembers() })
    }

    fun saveCommunityProfile(
        alias: String,
        bio: String,
        distinctive: String,
        onSuccess: () -> Unit
    ) {
        val id = communityId ?: return
        actions.run(id, "No se pudo guardar tu perfil de la comunidad. Reintenta.", { communityId == id },
            { communityRepository.updateCommunityMemberProfile(
                communityId = id,
                alias = alias,
                bio = bio,
                distinctive = distinctive
            ) }, { value ->
                val profile = value
                _communityProfile.value = profile
                _communityProfiles.value = _communityProfiles.value + (
                    profile.userId.trim().lowercase() to profile
                )
                _actionMessage.value = "Tu perfil de la comunidad se guardó."
                onSuccess()
            })
    }

    fun createCommunityEvent(
        title: String,
        description: String,
        startsAt: String,
        location: String,
        onSuccess: () -> Unit
    ) {
        val id = communityId ?: return
        actions.run(id, "No se pudo crear el evento. Comprueba tus permisos y reintenta.", { communityId == id },
            { communityRepository.createCommunityEvent(
                communityId = id,
                title = title,
                description = description,
                startsAt = startsAt,
                endsAt = null,
                location = location
            ) }, { value ->
                val event = value
                _events.value = (_events.value + event)
                    .distinctBy { it.id }
                    .sortedBy { it.startsAt }
                _actionMessage.value = "Evento creado."
                onSuccess()
            })
    }

    fun setEventAttendance(
        event: CommunityEvent,
        attending: Boolean,
        reminderMinutes: Int?
    ) {
        val id = communityId ?: return
        actions.run(id, "No se pudo guardar tu asistencia. Reintenta.", { communityId == id },
            { communityRepository.updateCommunityEventAttendance(
                communityId = id,
                eventId = event.id,
                attending = attending,
                reminderMinutes = reminderMinutes
            ) }, { value ->
                replaceEvent(value)
                _actionMessage.value = when {
                    !attending -> "Ya no asistirás a este evento."
                    reminderMinutes != null -> "Asistencia y recordatorio guardados."
                    else -> "Tu asistencia quedó confirmada."
                }
            })
    }

    fun cancelCommunityEvent(event: CommunityEvent) {
        val id = communityId ?: return
        actions.run(id, "No se pudo cancelar el evento. Reintenta.", { communityId == id },
            { communityRepository.cancelCommunityEvent(id, event.id) }, { value ->
                replaceEvent(value)
                _actionMessage.value = "Evento cancelado."
            })
    }

    fun updateCommunityInfo(
        name: String,
        description: String?,
        imageBytes: ByteArray?,
        isPublic: Boolean,
        onlyAdminsCanTalk: Boolean,
        weeklyMessageLimit: Int,
        onSuccess: () -> Unit
    ) {
        val id = communityId ?: return
        actions.run(id, "No se pudo guardar la información de la comunidad. Reintenta.", { communityId == id },
            { communityRepository.updateCommunityInfo(
                communityId = id,
                name = name,
                description = description,
                imageBytes = imageBytes,
                isPublic = isPublic,
                onlyAdminsCanTalk = onlyAdminsCanTalk,
                weeklyMessageLimit = weeklyMessageLimit
            ) }, { value ->
                _community.value = _community.value?.copy(
                    name = name.trim(),
                    description = description?.trim().orEmpty().ifBlank { null },
                    is_public = isPublic,
                    only_admins_can_talk = onlyAdminsCanTalk,
                    daily_message_limit = 0,
                    weekly_message_limit = weeklyMessageLimit.coerceIn(0, 10000)
                )
                onSuccess()
            })
    }

    private fun loadMembers() {
        val ticket = detailSession.current() ?: return
        val id = ticket.communityId
        if (membersLoadJob?.isActive == true) return
        membersLoadJob = ticket.scope.launch {
            val membersResult = conectate.com.core.network.runReadWithDeadline(10_000) { communityRepository.getCommunityMembers(id).getOrThrow() }
            if (!detailSession.isCurrent(ticket, authService.getCurrentUserId())) return@launch
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
        val ticket = detailSession.current() ?: return
        val id = ticket.communityId
        if (featuresLoadJob?.isActive == true) return
        featuresLoadJob = ticket.scope.launch {
        coroutineScope {
        launch {
            val profileResult = conectate.com.core.network.runReadWithDeadline(10_000) { communityRepository.getCommunityMemberProfiles(id).getOrThrow() }
            if (!detailSession.isCurrent(ticket, authService.getCurrentUserId())) return@launch
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
        launch {
            val eventsResult = conectate.com.core.network.runReadWithDeadline(10_000) { communityRepository.getCommunityEvents(id).getOrThrow() }
            if (!detailSession.isCurrent(ticket, authService.getCurrentUserId())) return@launch
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
