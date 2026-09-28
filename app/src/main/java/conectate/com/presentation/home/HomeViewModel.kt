package conectate.com.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.GroupRepository
import conectate.com.data.repository.CommunityRepository
import conectate.com.data.repository.VideoRepository
import conectate.com.data.repository.MomentRepository
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.messages.normalizedVisibleConversations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authService: AuthService,
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val groupRepository: GroupRepository,
    private val communityRepository: CommunityRepository,
    private val videoRepository: VideoRepository,
    private val momentRepository: MomentRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val sectionVisits = context.getSharedPreferences("home_section_visits", Context.MODE_PRIVATE)
    private val _newFollowingVideos = MutableStateFlow(false)
    val newFollowingVideos: StateFlow<Boolean> = _newFollowingVideos.asStateFlow()
    private val _newFollowingMoments = MutableStateFlow(false)
    val newFollowingMoments: StateFlow<Boolean> = _newFollowingMoments.asStateFlow()

    fun markSectionSeen(section: String) {
        val userId = authService.getCurrentUserId() ?: return
        sectionVisits.edit().putString("$userId:$section", Instant.now().toString()).apply()
        when (section) {
            "videos" -> _newFollowingVideos.value = false
            "social" -> _newFollowingMoments.value = false
        }
    }

    private fun lastSectionVisit(userId: String, section: String): Instant {
        val key = "$userId:$section"
        val saved = sectionVisits.getString(key, null)
        if (saved == null) {
            val now = Instant.now()
            sectionVisits.edit().putString(key, now.toString()).apply()
            return now
        }
        return runCatching { Instant.parse(saved) }.getOrDefault(Instant.now())
    }

    private suspend fun refreshNewContent(userId: String) {
        lastSectionVisit(userId, "videos")
        val momentsSince = lastSectionVisit(userId, "social")
        runCatching {
            val videos = videoRepository.getFollowingVideos().toList().lastOrNull().orEmpty()
            val currentVisit = lastSectionVisit(userId, "videos")
            _newFollowingVideos.value = videos.any {
                runCatching { Instant.parse(it.created_at).isAfter(currentVisit) }.getOrDefault(false)
            }
        }
        runCatching {
            momentRepository.refreshMoments()
            val moments = momentRepository.getMoments().first()
            val candidates = moments.filter {
                it.userId != userId && runCatching {
                    Instant.parse(it.createdAt.orEmpty()).isAfter(momentsSince)
                }.getOrDefault(false)
            }
            val following = userRepository.getFollowedUserIds(candidates.map { it.userId }).getOrThrow()
            val currentVisit = lastSectionVisit(userId, "social")
            _newFollowingMoments.value = candidates.any {
                it.userId.lowercase() in following && runCatching {
                    Instant.parse(it.createdAt.orEmpty()).isAfter(currentVisit)
                }.getOrDefault(false)
            }
        }
    }

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
        viewModelScope.launch {
            authService.currentSession.map { authService.getCurrentUserId() }.distinctUntilChanged().collectLatest { userId ->
                _newFollowingVideos.value = false
                _newFollowingMoments.value = false
                if (userId.isNullOrBlank()) return@collectLatest
                while (true) {
                    refreshNewContent(userId)
                    kotlinx.coroutines.delay(120_000L)
                }
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
