package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.User
import conectate.com.data.repository.FriendRepository
import conectate.com.data.repository.NotificationRepository
import conectate.com.data.local.prefs.SeenFriendRequestsPreferences
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject

@HiltViewModel
class FriendRequestsViewModel @Inject constructor(
    private val friendRepository: FriendRepository,
    private val dismissedRequestsPreferences: conectate.com.data.local.prefs.DismissedRequestsPreferences,
    private val seenRequestsPreferences: SeenFriendRequestsPreferences,
    private val authService: AuthService,
    private val notificationRepository: NotificationRepository,
) : ViewModel() {

    private val _requests = MutableStateFlow(friendRepository.cachedPendingRequests().orEmpty())
    val requests: StateFlow<List<User>> = _requests.asStateFlow()

    // A new screen has no server answer yet. Do not show an empty state while
    // the first request is still being scheduled by the lifecycle observer.
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()
    private val _recentLoadError = MutableStateFlow(false)
    val recentLoadError: StateFlow<Boolean> = _recentLoadError.asStateFlow()
    private val _unseenRequestCount = MutableStateFlow(0)
    val unseenRequestCount: StateFlow<Int> = _unseenRequestCount.asStateFlow()
    private var pendingIds: Set<String> = emptySet()
    private var pendingOwnerId: String? = null
    private var loadJob: Job? = null
    private var loadGeneration = 0L

    // Track accepted requests (local + recent friends) to show "Confirmed" state
    private val _acceptedRequests = MutableStateFlow<Map<String, Long>>(emptyMap())
    val acceptedRequests: StateFlow<Map<String, Long>> = _acceptedRequests.asStateFlow()

    init {
        viewModelScope.launch {
            seenRequestsPreferences.revision.collect {
                updateUnseenCount()
            }
        }
        viewModelScope.launch {
            var previousUnread: Int? = null
            notificationRepository.unreadCount.collect { count ->
                val previous = previousUnread
                previousUnread = count
                // Social keeps its own instance of this ViewModel while visible.
                // Refresh it when a notification arrives, not only on screen resume.
                if (previous != null && count > previous) loadRequests()
            }
        }
    }

    fun loadRequests() {
        val generation = ++loadGeneration
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val ownerId = authService.getCurrentUserId() ?: run {
                pendingIds = emptySet()
                pendingOwnerId = null
                _requests.value = emptyList()
                _acceptedRequests.value = emptyMap()
                _unseenRequestCount.value = 0
                _loadError.value = false
                _recentLoadError.value = false
                _isLoading.value = false
                return@launch
            }
            if (pendingOwnerId != null && pendingOwnerId != ownerId) {
                pendingIds = emptySet()
                _requests.value = emptyList()
                _acceptedRequests.value = emptyMap()
                _unseenRequestCount.value = 0
            }
            _isLoading.value = true
            _recentLoadError.value = false
            if (_requests.value.isEmpty()) {
                _requests.value = friendRepository.cachedPendingRequests().orEmpty()
            }
            try {
                coroutineScope {
                    val recent = async { friendRepository.getRecentFriends(10) }
                    val pendingList = friendRepository.getPendingRequests().getOrNull()
                        ?.distinctBy { it.id.lowercase() }
                    if (generation != loadGeneration || authService.getCurrentUserId() != ownerId || pendingList == null) {
                        recent.cancel()
                        if (generation == loadGeneration && authService.getCurrentUserId() == ownerId) _loadError.value = true
                        return@coroutineScope
                    }
                    pendingOwnerId = ownerId
                    pendingIds = pendingList.map { it.id }.toSet()
                    val dismissedIds = dismissedRequestsPreferences.getDismissedRequests()
                    // Keep already confirmed people visible while their independent read is pending.
                    val retained = mergeFriendRequests(pendingList, null, _requests.value, _acceptedRequests.value, dismissedIds)
                    _requests.value = retained.users
                    _acceptedRequests.value = retained.confirmations
                    _loadError.value = false
                    updateUnseenCount()
                    // Pending requests can be shown immediately. If that list is empty,
                    // wait for recent confirmations before declaring the screen empty.
                    if (pendingList.isNotEmpty()) _isLoading.value = false

                    val recentResult = recent.await()
                    if (generation != loadGeneration || authService.getCurrentUserId() != ownerId) return@coroutineScope
                    val refreshed = mergeFriendRequests(pendingList, recentResult, retained.users, retained.confirmations,
                        dismissedRequestsPreferences.getDismissedRequests())
                    _requests.value = refreshed.users
                    _acceptedRequests.value = refreshed.confirmations
                    _recentLoadError.value = refreshed.recentFailed
                }
            } finally {
                if (generation == loadGeneration) _isLoading.value = false
            }
        }
    }

    fun markLoadedRequestsViewed() {
        val ownerId = authService.getCurrentUserId() ?: return
        if (_loadError.value || _isLoading.value || pendingOwnerId != ownerId) return
        seenRequestsPreferences.markSeen(ownerId, pendingIds)
        updateUnseenCount()
    }

    private fun updateUnseenCount() {
        val ownerId = authService.getCurrentUserId()
        _unseenRequestCount.value = if (ownerId != null && ownerId == pendingOwnerId) {
            seenRequestsPreferences.unseenCount(ownerId, pendingIds)
        } else 0
    }

    fun acceptRequest(user: User) {
        viewModelScope.launch {
            // Optimistic update
            val timestamp = System.currentTimeMillis()
            _acceptedRequests.value = _acceptedRequests.value + (user.id to timestamp)
            
            val result = friendRepository.acceptFriendRequest(user.id)
            if (result.isFailure) {
                _acceptedRequests.value = _acceptedRequests.value - user.id
                loadRequests()
            }
        }
    }
    
    fun rejectRequest(user: User) {
        viewModelScope.launch {
            // Optimistic remove
            _requests.value = _requests.value.filter { it.id != user.id }
            
            val result = friendRepository.rejectFriendRequest(user.id)
            if (result.isFailure) {
                loadRequests()
            }
        }
    }
    
    fun dismissConfirmedRequest(userId: String) {
        dismissedRequestsPreferences.dismissRequest(userId)
        // Remove from list
        _requests.value = _requests.value.filter { it.id != userId }
    }
}

