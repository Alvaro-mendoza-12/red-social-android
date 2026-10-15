package conectate.com.presentation.social

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Moment
import conectate.com.data.model.MomentView
import conectate.com.data.repository.MomentRepository
import conectate.com.data.repository.MomentHighlightRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MomentViewModel @Inject constructor(
    private val repository: MomentRepository,
    private val highlightRepository: MomentHighlightRepository,
    private val messageRepository: conectate.com.data.repository.MessageRepository,
    private val authService: AuthService
) : ViewModel() {

    val currentUserId: String? get() = authService.getCurrentUserId()

    private val _moments = MutableStateFlow<List<Moment>>(emptyList())
    val moments: StateFlow<List<Moment>> = _moments.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(true)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _uploadError = MutableStateFlow<String?>(null)
    val uploadError = _uploadError.asStateFlow()

    private val _viewers = MutableStateFlow<List<MomentView>>(emptyList())
    val viewers: StateFlow<List<MomentView>> = _viewers.asStateFlow()
    
    init {
        // Start persistent background collection of the repository's state flow
        android.util.Log.d("MomentDebug", "MomentViewModel init - starting persistent flow collection")
        repository.getMoments()
            .onEach { list ->
                android.util.Log.d("MomentDebug", "MomentViewModel received moments update: ${list.size}")
                _moments.value = list
            }
            .launchIn(viewModelScope)
            
        // Initial manual trigger for the repository to start its observation if it hasn't
        refreshMoments()
    }

    /**
     * Triggers a network refresh from the repository.
     * Note: Observation of results is handled by the persistent flow in [init].
     */
    fun refreshMoments() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                android.util.Log.d("MomentDebug", "MomentViewModel manually triggering repository refresh")
                repository.refreshMoments()
            } catch (e: Exception) {
                android.util.Log.e("MomentDebug", "Error in manual refresh", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refreshAuthorMoments(ownerIds: List<String>) {
        viewModelScope.launch { repository.refreshMomentsForAuthors(ownerIds) }
    }

    // Helper to manually add a moment (optimistic update)
    fun addLocalMoment(moment: Moment) {
        val currentList = _moments.value.toMutableList()
        // Add to beginning or appropriate position based on logic, usually beginning for new
        currentList.add(0, moment) 
        _moments.value = currentList
    }

    fun uploadMoments(
        uris: List<Uri>,
        mediaTypes: List<String>,
        durationHours: Int,
        durationType: String,
        visibility: String,
        visibilityType: String,
        excludedIds: List<String>,
        caption: String? = null,
        allowReplies: Boolean = true,
        onSuccess: () -> Unit
    ) {
        val currentActiveCount = _moments.value.count { it.userId == currentUserId }
        if (currentActiveCount + uris.size > 10) {
            _uploadError.value = "Ya tienes $currentActiveCount momentos activos. El límite total es 10."
            return
        }

        viewModelScope.launch {
            _isUploading.value = true
            _uploadError.value = null
            var successCount = 0
            
            uris.forEachIndexed { index, uri ->
                val result = repository.createMoment(
                    mediaUri = uri, 
                    mediaType = mediaTypes.getOrElse(index) { "image" }, 
                    durationHours = durationHours, 
                    durationType = durationType,
                    visibility = visibility, 
                    visibilityType = visibilityType,
                    excludedIds = excludedIds,
                    caption = caption,
                    allowReplies = allowReplies
                )
                if (result.isSuccess) {
                    successCount++
                    // Optimistic update is now handled by the repository's flow
                    // result.getOrNull()?.let { newMoment ->
                    //     addLocalMoment(newMoment)
                    // }
                } else {
                    _uploadError.value = "Error al subir el elemento ${index + 1}: ${result.exceptionOrNull()?.message}"
                }
            }
            
            _isUploading.value = false
            if (successCount == uris.size) {
                onSuccess()
            } else if (successCount > 0) {
                // Partial success
                onSuccess()
            }
        }
    }

    fun recordView(momentId: String) {
        // Repository handles the optimistic update now
        viewModelScope.launch {
            repository.recordView(momentId)
        }
    }

    fun loadViewers(momentId: String) {
        viewModelScope.launch {
            repository.getMomentViewers(momentId).onSuccess {
                _viewers.value = it
            }
        }
    }

    fun deleteMoment(momentId: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            android.util.Log.d("MomentViewModel", "Deleting moment: $momentId")
            
            // 1. Optimistic Update: Remove from list immediately
            val currentList = _moments.value.toMutableList()
            val wasRemoved = currentList.removeAll { it.id == momentId }
            if (wasRemoved) {
                 _moments.value = currentList
            }

            // 2. Perform API Call
            repository.deleteMoment(momentId).onSuccess {
                android.util.Log.d("MomentViewModel", "Moment deleted successfully from server")
                
                // 3. Confirm with Refresh (Background)
                try {
                    repository.refreshMoments()
                } catch (e: Exception) {
                    android.util.Log.e("MomentViewModel", "Error refreshing after deletion", e)
                }
                
                onSuccess()
            }.onFailure { e ->
                android.util.Log.e("MomentViewModel", "Failed to delete moment", e)
                // Optional: Revert optimistic update if needed, but usually we just show error
                // _moments.value = currentList + moment // tough to revert without keeping ref
            }
        }
    }

    fun saveMomentToHighlight(
        moment: Moment,
        title: String,
        onResult: (Result<conectate.com.data.model.MomentHighlight>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(highlightRepository.saveMomentToHighlight(moment, title))
        }
    }

    fun toggleLike(moment: Moment, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            onResult(repository.setMomentLiked(moment.id, !moment.isLikedByMe))
        }
    }

    fun sendReply(momentId: String, text: String, receiverId: String, onResult: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val messageText = "Respondió a tu estado: $text"
            
            // Resolve Chat ID first
            val chatResult = messageRepository.getOrCreateChat(receiverId)
            chatResult.onSuccess { chat ->
                val result = messageRepository.sendMessage(
                    chatId = chat.id,
                    content = messageText,
                    mediaType = "text"
                )
                onResult(result.map { Unit })
            }.onFailure {
                // Determine if we should fail or try a direct send (if Repository supports it, but it seems it needs chatId)
                android.util.Log.e("MomentViewModel", "Failed to resolve chat for reply", it)
                onResult(Result.failure(it))
            }
        }
    }
}
