package conectate.com.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.UserRepository
import conectate.com.data.local.prefs.AppSettingsPreferences
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.GroupRepository
import conectate.com.data.repository.CommunityRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authService: AuthService,
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val groupRepository: GroupRepository,
    private val communityRepository: CommunityRepository,
    appSettingsPreferences: AppSettingsPreferences
) : ViewModel() {

    private val _currentUserAvatarUrl = MutableStateFlow<String?>(null)
    val currentUserAvatarUrl: StateFlow<String?> = _currentUserAvatarUrl.asStateFlow()

    // 1. Unread DMs (Number of conversations with unread messages)
    val unreadDmsCount: StateFlow<Int> = messageRepository.getUserChatsFlow()
        .map { chats -> chats.count { it.unreadCount > 0 && !it.chat.is_group && !it.chat.is_archived } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val bottomNavigationOrder: StateFlow<List<String>> = appSettingsPreferences.bottomNavigationOrder

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

    init {
        loadCurrentUserAvatar()
    }

    fun refreshShellUserData() {
        loadCurrentUserAvatar()
    }

    private fun loadCurrentUserAvatar() {
        viewModelScope.launch {
            val userId = authService.getCurrentUserId() ?: run {
                _currentUserAvatarUrl.value = null
                return@launch
            }

            val result = userRepository.getUserById(userId)
            _currentUserAvatarUrl.value = result.getOrNull()?.profileImage?.ifBlank { null }
        }
    }
}
