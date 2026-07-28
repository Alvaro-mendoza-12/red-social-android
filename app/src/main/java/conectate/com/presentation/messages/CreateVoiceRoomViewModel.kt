package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.VoiceRoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateVoiceRoomViewModel @Inject constructor(
    val repository: VoiceRoomRepository // Made public for direct access legacy support
) : ViewModel() {

    private val _isCreating = MutableStateFlow(false)
    val isCreating = _isCreating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun createRoom(
        name: String,
        description: String?,
        isPublic: Boolean,
        password: String?,
        roomMode: String,
        onSuccess: (String) -> Unit
    ) {
        if (name.isBlank()) {
            _error.value = "El nombre es obligatorio"
            return
        }

        if (_isCreating.value) return

        viewModelScope.launch {
            _isCreating.value = true
            _error.value = null
            try {
                val result = repository.createRoom(name, description, isPublic, password, roomMode)
                if (result.isSuccess) {
                    val room = result.getOrThrow()
                    onSuccess(room.id)
                } else {
                    _error.value = result.exceptionOrNull()?.message
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isCreating.value = false
            }
        }
    }
}
