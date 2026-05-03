package conectate.com.presentation.social

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.PostRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreatePostViewModel @Inject constructor(
    private val postRepository: PostRepository
) : ViewModel() {

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _uploadSuccess = MutableSharedFlow<Unit>()
    val uploadSuccess = _uploadSuccess.asSharedFlow()

    fun createPost(
        caption: String,
        mediaUris: List<Uri>,
        visibility: String = "public"
    ) {
        viewModelScope.launch {
            _isCreating.value = true
            try {
                val result = postRepository.createPost(caption, mediaUris, visibility)
                if (result.isSuccess) {
                    _uploadSuccess.emit(Unit)
                } else {
                    _error.value = result.exceptionOrNull()?.message ?: "Error desconocido"
                }
            } catch (e: Exception) {
                _error.value = "Error creating post: ${e.message}"
            } finally {
                _isCreating.value = false
            }
        }
    }
    
    fun clearError() {
        _error.value = null
    }
}
