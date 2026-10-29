package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.VoiceRoomRepository
import conectate.com.data.model.VoiceRoom
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

@HiltViewModel
class CreateVoiceRoomViewModel @Inject constructor(
    val repository: VoiceRoomRepository, // Made public for direct access legacy support
    private val authService: conectate.com.data.supabase.AuthService,
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
        onSuccess: (VoiceRoom) -> Unit
    ) {
        if (name.isBlank()) {
            _error.value = "El nombre es obligatorio"
            return
        }

        if (_isCreating.value) return
        val owner = authService.getCurrentUserId() ?: run { _error.value = "Inicia sesión para continuar."; return }

        _isCreating.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val result = repository.createRoom(name, description, isPublic, password, roomMode)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (owner != authService.getCurrentUserId()) return@launch
                if (result.isSuccess) {
                    val room = result.getOrThrow()
                    onSuccess(room)
                } else {
                    _error.value = result.exceptionOrNull()?.message
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isCreating.value = false
            }
        }
    }
}
