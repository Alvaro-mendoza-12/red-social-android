package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.VoiceRoom
import conectate.com.data.repository.VoiceRoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _uiState = MutableStateFlow<VoiceRoomsUiState>(VoiceRoomsUiState.Loading)
    val uiState: StateFlow<VoiceRoomsUiState> = _uiState.asStateFlow()
    val currentRoom = voiceRoomRepository.currentRoom
    val currentParticipants = voiceRoomRepository.currentParticipants

    init {
        viewModelScope.launch {
            voiceRoomRepository.activeRooms.collect { rooms ->
                _uiState.value = VoiceRoomsUiState.Success(rooms)
            }
        }
    }

    fun refreshRooms() {
        viewModelScope.launch {
            _uiState.value = VoiceRoomsUiState.Loading
            voiceRoomRepository.refreshRooms()
        }
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

    fun toggleMute(isMuted: Boolean) {
        voiceRoomRepository.toggleMute(isMuted)
    }

    fun leaveCurrentRoom() {
        viewModelScope.launch {
            voiceRoomRepository.leaveRoom()
        }
    }
}
