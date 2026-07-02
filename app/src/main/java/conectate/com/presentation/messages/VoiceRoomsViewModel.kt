package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.VoiceRoom
import conectate.com.data.repository.VoiceRoomRepository
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
    data class Success(val rooms: List<VoiceRoom>) : VoiceRoomsUiState()
    data class SearchResult(val room: VoiceRoom?) : VoiceRoomsUiState()
    data class Error(val message: String) : VoiceRoomsUiState()
}

@HiltViewModel
class VoiceRoomsViewModel @Inject constructor(
    private val voiceRoomRepository: VoiceRoomRepository
) : ViewModel() {
    private var hasCompletedInitialLoad = false

    private val _uiState = MutableStateFlow<VoiceRoomsUiState>(VoiceRoomsUiState.Loading)
    val uiState: StateFlow<VoiceRoomsUiState> = _uiState.asStateFlow()
    private val _hasAccess = MutableStateFlow(true)
    val hasAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()
    private val _accessRequestStatus = MutableStateFlow<String?>(null)
    val accessRequestStatus: StateFlow<String?> = _accessRequestStatus.asStateFlow()
    private val _errorEvents = Channel<String>(Channel.CONFLATED)
    val errorEvents = _errorEvents.receiveAsFlow()
    val currentRoom = voiceRoomRepository.currentRoom
    val currentParticipants = voiceRoomRepository.currentParticipants

    init {
        viewModelScope.launch {
            voiceRoomRepository.activeRooms.collect { rooms ->
                if (rooms.isNotEmpty() || hasCompletedInitialLoad) {
                    _uiState.value = VoiceRoomsUiState.Success(rooms)
                }
            }
        }
        refreshRooms()
    }

    fun refreshRooms() {
        viewModelScope.launch {
            _uiState.value = VoiceRoomsUiState.Loading
            voiceRoomRepository.refreshRooms()
                .onSuccess { rooms ->
                    hasCompletedInitialLoad = true
                    _uiState.value = VoiceRoomsUiState.Success(rooms)
                }
                .onFailure { error ->
                    val message = error.message ?: "No se pudieron cargar las salas de voz"
                    val cachedRooms = voiceRoomRepository.activeRooms.value
                    _uiState.value = if (cachedRooms.isNotEmpty()) {
                        VoiceRoomsUiState.Success(cachedRooms)
                    } else {
                        VoiceRoomsUiState.Error(message)
                    }
                    _errorEvents.trySend(message)
                }
        }
    }

    fun refreshStatus() {
        _hasAccess.value = true
    }

    fun requestAccess() {
        _hasAccess.value = true
        _accessRequestStatus.value = null
        refreshRooms()
    }

    fun searchRoomById(id: String) {
        viewModelScope.launch {
            _uiState.value = VoiceRoomsUiState.Loading
            voiceRoomRepository.getRoomById(id).fold(
                onSuccess = { room ->
                    _uiState.value = VoiceRoomsUiState.SearchResult(room)
                },
                onFailure = {
                    _uiState.value = VoiceRoomsUiState.Error("Sala no encontrada")
                }
            )
        }
    }

    fun resetSearch() {
        refreshRooms()
    }

    fun deleteRoom(roomId: String) {
        viewModelScope.launch {
            voiceRoomRepository.deleteRoom(roomId)
                .onFailure { error ->
                    _errorEvents.trySend(error.message ?: "No se pudo eliminar la sala")
                }
            refreshRooms()
        }
    }

    fun getCurrentUserId(): String = voiceRoomRepository.getCurrentUserId()?.trim()?.lowercase().orEmpty()

    fun toggleMute(isMuted: Boolean) {
        voiceRoomRepository.toggleMute(isMuted)
    }

    fun leaveCurrentRoom() {
        viewModelScope.launch {
            voiceRoomRepository.leaveRoom()
        }
    }
}
