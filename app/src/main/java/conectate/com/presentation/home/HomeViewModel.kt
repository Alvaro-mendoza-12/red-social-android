package conectate.com.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.UserRepository
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.GroupRepository
import conectate.com.data.repository.CommunityRepository
import conectate.com.data.repository.VideoRepository
import conectate.com.data.repository.MomentRepository
import conectate.com.data.repository.PostRepository
import conectate.com.data.repository.PlayMatchRepository
import conectate.com.data.repository.NotificationRepository
import conectate.com.data.model.PlayMatchConnection
import conectate.com.data.model.PlayMatchMatch
import conectate.com.data.supabase.AuthService
import conectate.com.presentation.messages.normalizedVisibleConversations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val postRepository: PostRepository,
    private val playMatchRepository: PlayMatchRepository,
    private val notificationRepository: NotificationRepository,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val sectionVisits = context.getSharedPreferences("home_section_visits", Context.MODE_PRIVATE)
    private val _newFollowingVideos = MutableStateFlow(false)
    val newFollowingVideos: StateFlow<Boolean> = _newFollowingVideos.asStateFlow()
    private val _newFollowingMoments = MutableStateFlow(false)
    private val _newFollowingPosts = MutableStateFlow(false)
    val newSocialContent = combine(_newFollowingPosts, _newFollowingMoments) { posts, moments -> posts || moments }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val _newGames = MutableStateFlow(false)
    val newGames = _newGames.asStateFlow()
    val unreadProfileNotifications = notificationRepository.unreadCount
    private val foreground = MutableStateFlow(false)
    private var latestVideoAt: Instant? = null
    private var latestPostAt: Instant? = null
    private var gameKeys: Set<String> = emptySet()

    fun setForeground(visible: Boolean) { foreground.value = visible }

    fun markFeedSeen(section: String, timestamps: List<String>) {
        val userId = authService.getCurrentUserId() ?: return
        val loadedThrough = HomeNoveltyPolicy.newest(timestamps) ?: return
        val seen = maxOf(lastSectionVisit(userId, section), loadedThrough)
        sectionVisits.edit().putString("$userId:$section", seen.toString()).apply()
        when (section) {
            "videos" -> _newFollowingVideos.value = HomeNoveltyPolicy.hasNewer(latestVideoAt, seen)
            "social" -> _newFollowingPosts.value = HomeNoveltyPolicy.hasNewer(latestPostAt, seen)
        }
    }

    fun markGamesSeen(connections: List<PlayMatchConnection>, matches: List<PlayMatchMatch>) {
        val userId = authService.getCurrentUserId() ?: return
        val loaded = HomeNoveltyPolicy.gameEvents(connections, matches)
        val seen = sectionVisits.getStringSet("$userId:games", emptySet()).orEmpty() + loaded
        sectionVisits.edit().putStringSet("$userId:games", seen.takeLastBounded(500)).apply()
        _newGames.value = (gameKeys - seen).isNotEmpty()
    }

    private fun Set<String>.takeLastBounded(limit: Int): Set<String> = toList().takeLast(limit).toSet()

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

    private suspend fun quietlyRefresh(block: suspend () -> Unit) {
        try { withTimeoutOrNull(12_000L) { block() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A failed refresh never acknowledges unseen content. */ }
    }

    private fun isCurrent(userId: String) = userId == authService.getCurrentUserId()

    private suspend fun refreshNewContent(userId: String) = coroutineScope {
        launch { quietlyRefresh {
            val videos = videoRepository.getFollowingVideos(emitCachedSnapshots = false, failOnError = true)
                .toList().lastOrNull().orEmpty()
            currentCoroutineContext().ensureActive()
            if (isCurrent(userId)) {
                latestVideoAt = HomeNoveltyPolicy.newest(videos.filter { it.userId != userId }.map { it.created_at })
                _newFollowingVideos.value = HomeNoveltyPolicy.hasNewer(latestVideoAt, lastSectionVisit(userId, "videos"))
            }
        } }
        launch { quietlyRefresh {
            val posts = postRepository.getGlobalPosts(mode = "following", emitCachedSnapshots = false)
                .toList().lastOrNull().orEmpty()
            if (postRepository.globalFeedError.value != null) return@quietlyRefresh
            currentCoroutineContext().ensureActive()
            if (isCurrent(userId)) {
                latestPostAt = HomeNoveltyPolicy.newest(posts.filter { it.userId != userId }.map { it.createdAt })
                _newFollowingPosts.value = HomeNoveltyPolicy.hasNewer(latestPostAt, lastSectionVisit(userId, "social"))
            }
        } }
        launch { quietlyRefresh { momentRepository.refreshMoments() } }
        launch { quietlyRefresh { notificationRepository.updateUnreadCount().getOrThrow() } }
        launch { quietlyRefresh {
            if (!playMatchRepository.capabilities().getOrThrow().enabled) return@quietlyRefresh
            val connections = playMatchRepository.connections().getOrThrow().items
            val matches = playMatchRepository.matches().getOrThrow().items
            currentCoroutineContext().ensureActive()
            if (isCurrent(userId)) {
                gameKeys = HomeNoveltyPolicy.gameEvents(connections, matches)
                val seen = sectionVisits.getStringSet("$userId:games", emptySet()).orEmpty()
                _newGames.value = (gameKeys - seen).isNotEmpty()
            }
        } }
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
            authService.currentSession.map { authService.getCurrentUserId() }.distinctUntilChanged().collect {
                _newFollowingVideos.value = false
                _newFollowingMoments.value = false
                _newFollowingPosts.value = false
                _newGames.value = false
                latestVideoAt = null
                latestPostAt = null
                gameKeys = emptySet()
            }
        }
        viewModelScope.launch {
            combine(authService.currentSession.map { authService.getCurrentUserId() }.distinctUntilChanged(), foreground) { userId, visible ->
                userId.takeIf { visible }
            }.distinctUntilChanged().collectLatest { userId ->
                if (userId.isNullOrBlank()) return@collectLatest
                lastSectionVisit(userId, "videos")
                lastSectionVisit(userId, "social")
                coroutineScope {
                    launch {
                        momentRepository.getMoments().collectLatest { moments -> quietlyRefresh {
                            val now = Instant.now()
                            val candidates = moments.filter {
                                it.userId != userId && !it.isViewed &&
                                    HomeNoveltyPolicy.instant(it.expiresAt)?.isAfter(now) == true
                            }
                            val following = if (candidates.isEmpty()) emptySet() else
                                userRepository.getFollowedUserIds(candidates.map { it.userId }.distinct()).getOrThrow()
                            if (isCurrent(userId)) _newFollowingMoments.value = candidates.any { it.userId.lowercase() in following }
                        } }
                    }
                    while (true) {
                        refreshNewContent(userId)
                        kotlinx.coroutines.delay(120_000L)
                    }
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
