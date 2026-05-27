package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.CommunityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateCommunityViewModel @Inject constructor(
    private val communityRepository: CommunityRepository
) : ViewModel() {

    private val _isCreating = MutableStateFlow(false)
    val isCreating = _isCreating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _selectedImageBytes = MutableStateFlow<ByteArray?>(null)
    val selectedImageBytes = _selectedImageBytes.asStateFlow()

    fun setImageBytes(bytes: ByteArray?) {
        _selectedImageBytes.value = bytes
    }

    fun createCommunity(
        name: String,
        description: String?,
        category: String?,
        isPublic: Boolean,
        onSuccess: (String) -> Unit
    ) {
        if (name.isBlank()) {
            _error.value = "El nombre es obligatorio"
            return
        }

        viewModelScope.launch {
            _isCreating.value = true
            _error.value = null
            try {
                val result = communityRepository.createCommunity(
                    name = name,
                    description = description,
                    imageBytes = _selectedImageBytes.value,
                    category = category,
                    isPublic = isPublic
                )
                if (result.isSuccess) {
                    onSuccess(result.getOrThrow())
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
