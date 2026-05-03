package conectate.com.presentation.voicerooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class VoiceRoomsUiState {
    object Loading : VoiceRoomsUiState()
    data class Success(
        val rooms: List<conectate.com.data.model.VoiceRoom> = emptyList()
    ) : VoiceRoomsUiState()
    data class SearchResult(
        val rooms: List<conectate.com.data.model.VoiceRoom> = emptyList()
    ) : VoiceRoomsUiState()
    data class Error(val message: String) : VoiceRoomsUiState()
}

@HiltViewModel
class VoiceRoomsViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow<VoiceRoomsUiState>(VoiceRoomsUiState.Loading)
    val uiState: StateFlow<VoiceRoomsUiState> = _uiState.asStateFlow()

    private val _isCreatingRoom = MutableStateFlow(false)
    val isCreatingRoom: StateFlow<Boolean> = _isCreatingRoom.asStateFlow()

    private val _hasAccess = MutableStateFlow(true)
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private val _accessRequestStatus = MutableStateFlow<String?>(null)
    val accessRequestStatus: StateFlow<String?> = _accessRequestStatus.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _activeRoomId = MutableStateFlow<String?>(null)
    val activeRoomId: StateFlow<String?> = _activeRoomId.asStateFlow()

    private val _errorEvents = Channel<String>(Channel.CONFLATED)
    val errorEvents = _errorEvents.receiveAsFlow()

    init {
        loadRooms()
    }

    fun loadRooms() {
        viewModelScope.launch {
            _uiState.value = VoiceRoomsUiState.Success(emptyList())
        }
    }

    fun refreshRooms() {
        loadRooms()
    }

    fun refreshStatus() {
        viewModelScope.launch {
            _hasAccess.value = true
        }
    }

    fun searchRoomById(id: String) {
        viewModelScope.launch {
            _uiState.value = VoiceRoomsUiState.SearchResult(emptyList())
        }
    }

    fun resetSearch() {
        loadRooms()
    }

    fun requestAccess() {
        viewModelScope.launch {
            _accessRequestStatus.value = "pending"
        }
    }

    fun deleteRoom(roomId: String) {
        viewModelScope.launch {
        }
    }

    fun getCurrentUserId(): String = ""

    fun getActiveRoomId(): String? = _activeRoomId.value
}
