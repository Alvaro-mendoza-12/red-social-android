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
    private val postRepository: PostRepository,
    private val userRepository: conectate.com.data.repository.UserRepository,
    private val authService: conectate.com.data.supabase.AuthService,
) : ViewModel() {

    val currentAccountId: String? get() = authService.getCurrentUserId()

    private val _mentionSuggestions = MutableStateFlow<List<conectate.com.data.model.User>>(emptyList())
    val mentionSuggestions = _mentionSuggestions.asStateFlow()
    private var mentionJob: kotlinx.coroutines.Job? = null
    private var mentionGeneration = 0L

    fun updateMentionQuery(text: String, cursor: Int) {
        mentionJob?.cancel()
        val generation = ++mentionGeneration
        _mentionSuggestions.value = emptyList()
        val query = conectate.com.data.model.activeMentionAt(text, cursor)?.query?.takeIf { it.isNotBlank() } ?: return
        val account = authService.getCurrentUserId() ?: return
        mentionJob = viewModelScope.launch {
            kotlinx.coroutines.delay(180L)
            val found = userRepository.searchMentionCandidates(query, excludeUserId = account, limit = 6)
            if (generation == mentionGeneration && authService.getCurrentUserId() == account)
                _mentionSuggestions.value = found.getOrDefault(emptyList())
        }
    }

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _uploadSuccess = MutableSharedFlow<Unit>()
    val uploadSuccess = _uploadSuccess.asSharedFlow()

    fun createPost(
        caption: String,
        mediaUris: List<Uri>,
        visibility: String = "public",
        remixAllowed: Boolean = false,
        remixAudience: String = "public",
        isSensitive: Boolean = false
    ) {
        if (_isCreating.value) return
        _isCreating.value = true
        viewModelScope.launch {
            try {
                val result = postRepository.createPost(
                    content = caption,
                    mediaUris = mediaUris,
                    visibility = visibility,
                    remixAllowed = remixAllowed,
                    remixAudience = remixAudience,
                    isSensitive = isSensitive
                )
                if (result.isSuccess) {
                    _uploadSuccess.emit(Unit)
                } else {
                    _error.value = "No se pudo publicar. Comprueba la conexión y vuelve a intentarlo; conservamos tu borrador."
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error
            } catch (e: Exception) {
                _error.value = "No se pudo publicar. Comprueba la conexión y vuelve a intentarlo; conservamos tu borrador."
            } finally {
                _isCreating.value = false
            }
        }
    }
    
    fun clearError() {
        _error.value = null
    }
}
