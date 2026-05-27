package conectate.com.presentation.voicerooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.VoiceRoom
import conectate.com.data.repository.VoiceRoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class VoiceRoomSearchViewModel @Inject constructor(
    private val repository: VoiceRoomRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    val searchResults: StateFlow<List<VoiceRoom>> = repository.activeRooms
        .combine(_query) { rooms, query ->
            if (query.isBlank()) {
                emptyList()
            } else {
                rooms.filter { room ->
                    room.name.contains(query, ignoreCase = true) ||
                    (room.readableId?.equals(query, ignoreCase = true) == true)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }
}
