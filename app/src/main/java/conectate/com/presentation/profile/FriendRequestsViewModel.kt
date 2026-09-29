package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.User
import conectate.com.data.repository.FriendRepository
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
) : ViewModel() {

    private val _requests = MutableStateFlow<List<User>>(emptyList())
    val requests: StateFlow<List<User>> = _requests.asStateFlow()

    // A new screen has no server answer yet. Do not show an empty state while
    // the first request is still being scheduled by the lifecycle observer.
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()
    private val _unseenRequestCount = MutableStateFlow(0)
    val unseenRequestCount: StateFlow<Int> = _unseenRequestCount.asStateFlow()
    private var pendingIds: Set<String> = emptySet()
    private var pendingOwnerId: String? = null
    private var loadJob: Job? = null

    // Track accepted requests (local + recent friends) to show "Confirmed" state
    private val _acceptedRequests = MutableStateFlow<Map<String, Long>>(emptyMap())
    val acceptedRequests: StateFlow<Map<String, Long>> = _acceptedRequests.asStateFlow()

    init {
        viewModelScope.launch {
            seenRequestsPreferences.revision.collect {
                updateUnseenCount()
            }
        }
    }

    fun loadRequests() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val ownerId = authService.getCurrentUserId() ?: run {
                pendingIds = emptySet()
                pendingOwnerId = null
                _requests.value = emptyList()
                _acceptedRequests.value = emptyMap()
                _unseenRequestCount.value = 0
                _loadError.value = false
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
            try {
                coroutineScope {
                    val recent = async { friendRepository.getRecentFriends(10) }
                    val pendingList = friendRepository.getPendingRequests().getOrNull()
                    if (authService.getCurrentUserId() != ownerId || pendingList == null) {
                        recent.cancel()
                        if (authService.getCurrentUserId() == ownerId) _loadError.value = true
                        return@coroutineScope
                    }
                    pendingOwnerId = ownerId
                    pendingIds = pendingList.map { it.id }.toSet()
                    _requests.value = pendingList
                    _acceptedRequests.value = emptyMap()
                    _loadError.value = false
                    updateUnseenCount()
                    // Pending requests can be shown immediately. If that list is empty,
                    // wait for recent confirmations before declaring the screen empty.
                    if (pendingList.isNotEmpty()) _isLoading.value = false

                    val recentList = recent.await().getOrNull().orEmpty()
                    if (authService.getCurrentUserId() != ownerId) return@coroutineScope
                    val dismissedIds = dismissedRequestsPreferences.getDismissedRequests()
                    val visibleRecent = recentList.filter { it.first.id !in dismissedIds }
                    _acceptedRequests.value = visibleRecent.associate { (user, dateStr) ->
                        val time = runCatching { java.time.Instant.parse(dateStr).toEpochMilli() }
                            .getOrElse { System.currentTimeMillis() }
                        user.id to time
                    }
                    _requests.value = pendingList + visibleRecent.map { it.first }
                }
            } finally {
                _isLoading.value = false
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

