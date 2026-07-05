package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Community
import conectate.com.data.model.CommunityMember
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
    private val userRepository: conectate.com.data.repository.UserRepository
) : ViewModel() {

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

    private var communityId: String? = null

    init {
        val userId = authService.getCurrentUserId()
        _currentUserId.value = userId
    }

    fun loadCommunityDetails(id: String) {
        communityId = id
        _isLoading.value = true
        
        // 1. Force Sync
        viewModelScope.launch {
             try { communityRepository.syncCommunities() } catch (e: Exception) {}
        }

        // 2. Observe Community
        viewModelScope.launch {
            communityRepository.getCommunityFlow(id).collect { comm ->
                _community.value = comm
                if (comm != null) {
                    _isLoading.value = false
                    
                    val myId = _currentUserId.value
                    val amICreator = sameCommunityUserId(comm.creator_id, myId)
                    val amIAdmin = _members.value.find { sameCommunityUserId(it.user_id, myId) }
                        ?.role
                        .isCommunityAdminRole()
                    _isAdmin.value = amICreator || amIAdmin
                }
            }
        }
        
        // 3. Observe Membership Status
        viewModelScope.launch {
            val userId = _currentUserId.value ?: authService.getCurrentUserId()
            if (userId != null) {
                communityRepository.isUserMemberFlow(id, userId).collect { isMem ->
                    _isMember.value = isMem
                }
            }
        }

        // 4. Observe Members Reactively
        viewModelScope.launch {
            // Initial Load with names
            loadMembers()
            
            communityRepository.getMembersFlow(id).collect { basicMembers ->
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
                        loadMembers()
                    } else {
                        _members.value = enriched
                    }
                    
                    // Recalculate permissions on member update
                    val myId = _currentUserId.value
                    val comm = _community.value
                    val amICreator = sameCommunityUserId(comm?.creator_id, myId)
                    val amIAdmin = enriched.find { sameCommunityUserId(it.user_id, myId) }
                        ?.role
                        .isCommunityAdminRole()
                    _isAdmin.value = amICreator || amIAdmin
                }
            }
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

    fun promoteToAdmin(userId: String) {
        val id = communityId ?: return
        viewModelScope.launch {
            communityRepository.updateMemberRole(id, userId, "admin")
            loadMembers()
        }
    }

    fun demoteToMember(userId: String) {
        val id = communityId ?: return
        viewModelScope.launch {
            communityRepository.updateMemberRole(id, userId, "member")
            loadMembers()
        }
    }

    fun removeMember(userId: String) {
        val id = communityId ?: return
        viewModelScope.launch {
            communityRepository.removeMember(id, userId)
            loadMembers()
        }
    }

    fun updateCommunityInfo(
        name: String,
        description: String?,
        imageBytes: ByteArray?,
        isPublic: Boolean,
        onlyAdminsCanTalk: Boolean,
        onSuccess: () -> Unit
    ) {
        val id = communityId ?: return
        viewModelScope.launch {
            val result = communityRepository.updateCommunityInfo(id, name, description, imageBytes, isPublic, onlyAdminsCanTalk)
            if (result.isSuccess) {
                onSuccess()
            }
        }
    }

    private fun loadMembers() {
        val id = communityId ?: return
        viewModelScope.launch {
            val membersResult = communityRepository.getCommunityMembers(id)
            if (membersResult.isSuccess) {
                _members.value = membersResult.getOrDefault(emptyList())
            }
        }
    }
}

private fun sameCommunityUserId(left: String?, right: String?): Boolean {
    val cleanLeft = left?.trim()?.lowercase().orEmpty()
    val cleanRight = right?.trim()?.lowercase().orEmpty()
    return cleanLeft.isNotBlank() && cleanLeft == cleanRight
}

private fun String?.isCommunityAdminRole(): Boolean =
    this?.trim()?.lowercase() in setOf("admin", "owner", "creator")
