package conectate.com.presentation.voicerooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.VoiceRoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import conectate.com.data.model.VoiceRoom
import conectate.com.presentation.search.UnifiedSearchController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class VoiceRoomSearchViewModel @Inject constructor(
    private val voiceRoomRepository: VoiceRoomRepository
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val search = UnifiedSearchController<VoiceRoom, Unit>(viewModelScope,
        findUsers = { term -> voiceRoomRepository.refreshRooms().map { rooms ->
            rooms.filter { it.name.contains(term, true) || it.id.equals(term, true) || it.readableId?.contains(term, true) == true }.distinctBy { it.id }
        } }, findContent = { Result.success(emptyList()) })
    val searchResults = search.users
    val loading = search.loading
    val error = search.usersError

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery.take(120)
        search.search(_query.value)
    }
}
