package conectate.com.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.User
import conectate.com.data.repository.FriendRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FriendRequestsViewModel @Inject constructor(
    private val friendRepository: FriendRepository,
    private val dismissedRequestsPreferences: conectate.com.data.local.prefs.DismissedRequestsPreferences
) : ViewModel() {

    private val _requests = MutableStateFlow<List<User>>(emptyList())
    val requests: StateFlow<List<User>> = _requests.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Track accepted requests (local + recent friends) to show "Confirmed" state
    private val _acceptedRequests = MutableStateFlow<Map<String, Long>>(emptyMap())
    val acceptedRequests: StateFlow<Map<String, Long>> = _acceptedRequests.asStateFlow()

    init {
        loadRequests()
    }

    fun loadRequests() {
        viewModelScope.launch {
            _isLoading.value = true
            
            // 1. Fetch Pending Requests
            val pendingResult = friendRepository.getPendingRequests()
            val pendingList = pendingResult.getOrNull() ?: emptyList()
            
            // 2. Fetch Recent Friends (Confirmed requests)
            val recentResult = friendRepository.getRecentFriends(10)
            val recentList = recentResult.getOrNull() ?: emptyList()
            
            // 3. Filter out dismissed confirmed requests
            val dismissedIds = dismissedRequestsPreferences.getDismissedRequests()
            val visibleRecent = recentList.filter { it.first.id !in dismissedIds }
            
            // 4. Update acceptedRequests map with recent friends (using updated_at timestamp)
            val recentMap = visibleRecent.associate { (user, dateStr) ->
                // Parse date string to long if possible, else use current time
                 val time = try {
                     java.time.Instant.parse(dateStr).toEpochMilli()
                 } catch (e: Exception) {
                     System.currentTimeMillis()
                 }
                user.id to time
            }
            _acceptedRequests.value = recentMap
            
            // 5. Combine lists: Pending first, then Recent (Confirmed)
            // Note: We only add recent friends to the LIST if we want them displayed.
            // UI displays items from 'requests'.
            _requests.value = pendingList + visibleRecent.map { it.first }
            
            _isLoading.value = false
        }
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

