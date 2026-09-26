package conectate.com.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.GroupRepository
import conectate.com.data.repository.CommunityRepository
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.messages.normalizedVisibleConversations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authService: AuthService,
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val groupRepository: GroupRepository,
    private val communityRepository: CommunityRepository
) : ViewModel() {

    private val _currentUserAvatarUrl = MutableStateFlow<String?>(null)
    val currentUserAvatarUrl: StateFlow<String?> = _currentUserAvatarUrl.asStateFlow()

    // 1. Unread DMs (Number of conversations with unread messages)
    val unreadDmsCount: StateFlow<Int> = messageRepository.getUserChatsFlow()
        .map { chats ->
            normalizedVisibleConversations(chats)
                .count { it.unreadCount > 0 && !it.chat.is_group && !it.chat.is_archived }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // 2. Unread Groups
    val unreadGroupsCount: StateFlow<Int> = groupRepository.getGroupsFlow()
        .map { groups -> groups.count { it.unreadCount > 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // 3. Unread Communities
    val unreadCommunitiesCount: StateFlow<Int> = communityRepository.getCommunitiesFlow()
        .map { communities -> communities.count { it.unreadCount > 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // 4. Total Messages Badge (Sum of unique conversations)
    val totalUnreadConversations: StateFlow<Int> = combine(
        unreadDmsCount,
        unreadGroupsCount,
        unreadCommunitiesCount
    ) { dms, groups, communities ->
        dms + groups + communities
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var avatarJob: Job? = null
    private var avatarOwner: String? = null
    private var avatarGeneration = 0L

    init {
        viewModelScope.launch {
            authService.currentSession.map { authService.getCurrentUserId() }.distinctUntilChanged().collect {
                loadCurrentUserAvatar()
            }
        }
    }

    fun refreshShellUserData() {
        loadCurrentUserAvatar()
    }

    private fun loadCurrentUserAvatar() {
        val userId = authService.getCurrentUserId()
        if (avatarOwner == userId && avatarJob?.isActive == true) return
        val generation = ++avatarGeneration
        avatarJob?.cancel()
        if (avatarOwner != userId) _currentUserAvatarUrl.value = null
        avatarOwner = userId
        if (userId.isNullOrBlank()) return
        avatarJob = viewModelScope.launch {
            fun isCurrent() = generation == avatarGeneration && userId == authService.getCurrentUserId()
            try {
                val cached = userRepository.getCachedUserById(userId)
                if (isCurrent() && cached != null) _currentUserAvatarUrl.value = cached.profileImage.ifBlank { null }
                val result = userRepository.getUserById(userId)
                if (isCurrent() && result.isSuccess) {
                    _currentUserAvatarUrl.value = result.getOrNull()?.profileImage?.ifBlank { null }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the already loaded local avatar during network failures. */ }
        }
    }
}
