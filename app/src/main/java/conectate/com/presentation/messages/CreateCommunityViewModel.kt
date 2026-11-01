package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.CommunityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

@HiltViewModel
class CreateCommunityViewModel @Inject constructor(
    private val communityRepository: CommunityRepository,
    private val authService: conectate.com.data.supabase.AuthService,
) : ViewModel() {

    private val _isCreating = MutableStateFlow(false)
    val isCreating = _isCreating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val creationSession = CollectionCreationSession(viewModelScope, authService.captureAccountLease()) {
        _error.value = it
    }
    val selectedImageBytes = creationSession.selectedImageBytes
    val isPreparingImage = creationSession.preparingImage
    val sessionCurrent = creationSession.sessionCurrent
    fun isSessionCurrent() = creationSession.isCurrent()
    val ownerAccountId get() = creationSession.accountId
    fun prepareImage(loader: suspend () -> ByteArray?) {
        if (!_isCreating.value) creationSession.prepareImage(loader)
    }

    fun setImageBytes(bytes: ByteArray?) {
        if (!_isCreating.value) creationSession.setImageBytes(bytes)
    }

    fun createCommunity(
        name: String,
        description: String?,
        category: String?,
        isPublic: Boolean,
        onSuccess: (String) -> Unit
    ) {
        if (!creationSession.isCurrent() || _isCreating.value) return
        if (isPreparingImage.value) { _error.value = "Espera a que terminemos de preparar el icono."; return }
        if (name.isBlank()) {
            _error.value = "El nombre es obligatorio"
            return
        }

        val image = selectedImageBytes.value
        val owner = creationSession.accountId ?: run { _error.value = "Inicia sesión para continuar."; return }
        _isCreating.value = true
        _error.value = null
        creationSession.scope.launch {
            try {
                creationSession.requireCurrent()
                val result = communityRepository.createCommunity(
                    name = name,
                    description = description,
                    imageBytes = image,
                    category = category,
                    isPublic = isPublic,
                    expectedAccountId = owner,
                )
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                creationSession.requireCurrent()
                if (result.isSuccess) {
                    val id = result.getOrThrow()
                    if (id.isNotBlank()) onSuccess(id)
                    else _error.value = "No pudimos confirmar la comunidad. Reintenta."
                } else {
                    _error.value = "No pudimos crear la comunidad. Conservamos tus datos para reintentar."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (creationSession.isCurrent()) _error.value = "No pudimos completar la operación. Conservamos tus datos para reintentar."
            } finally {
                _isCreating.value = false
            }
        }
    }
    override fun onCleared() { creationSession.close(); super.onCleared() }

    init {
        viewModelScope.launch {
            authService.observeSession().collect {
                creationSession.sessionChanged()
                if (!creationSession.isCurrent()) {
                    _isCreating.value = false
                }
            }
        }
    }

}
