package conectate.com.presentation.voicerooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.VoiceRoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VoiceRoomSearchViewModel @Inject constructor(
    private val voiceRoomRepository: VoiceRoomRepository
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchResults = MutableStateFlow<List<conectate.com.data.model.VoiceRoom>>(emptyList())
    val searchResults: StateFlow<List<conectate.com.data.model.VoiceRoom>> = _searchResults.asStateFlow()

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class)
            _query
                .debounce(300)
                .filter { it.isNotBlank() }
                .distinctUntilChanged()
                .collect { q -> performSearch(q) }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        if (newQuery.isBlank()) {
            _searchResults.value = emptyList()
        }
    }

    private suspend fun performSearch(query: String) {
        val cleanQuery = query.trim()
        _searchResults.value = voiceRoomRepository.refreshRooms()
            .getOrElse { emptyList() }
            .filter { room ->
                room.name.contains(cleanQuery, ignoreCase = true) ||
                    room.id.equals(cleanQuery, ignoreCase = true) ||
                    room.readableId?.contains(cleanQuery, ignoreCase = true) == true
            }
    }
}
