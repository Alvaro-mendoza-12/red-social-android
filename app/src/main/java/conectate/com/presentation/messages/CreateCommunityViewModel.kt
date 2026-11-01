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
    private val creationJournal: conectate.com.data.repository.collection.CollectionCreationJournal,
) : ViewModel() {

    private val _isCreating = MutableStateFlow(false)
    val isCreating = _isCreating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val creationSession = CollectionCreationSession(viewModelScope, authService.captureAccountLease()) {
        _error.value = it
    }
    private val recovery = CollectionCreationRecovery(creationSession, {
        creationSession.accountId?.takeIf { authService.isLocalFallbackSession() }?.let { creationJournal.pending(it, "community") }
    }) { _error.value = it }
    internal val recoveryState = recovery.state
    fun retryRecovery() { if (!_isCreating.value) creationSession.scope.launch { recovery.refresh() } }
    fun retryPending(onSuccess: (String) -> Unit) {
        val pending = recovery.state.value.pending ?: return
        createCommunity(pending.name, pending.description, pending.category, pending.isPublic, onSuccess)
    }
    private fun editable() = creationSession.isCurrent() && !_isCreating.value && !recovery.state.value.showRecovery
    val selectedImageBytes = creationSession.selectedImageBytes
    val isPreparingImage = creationSession.preparingImage
    val sessionCurrent = creationSession.sessionCurrent
    fun isSessionCurrent() = creationSession.isCurrent()
    val ownerAccountId get() = creationSession.accountId
    fun prepareImage(loader: suspend () -> ByteArray?) {
        if (editable()) creationSession.prepareImage(loader)
    }

    fun setImageBytes(bytes: ByteArray?) {
        if (editable()) creationSession.setImageBytes(bytes)
    }

    fun createCommunity(
        name: String,
        description: String?,
        category: String?,
        isPublic: Boolean,
        onSuccess: (String) -> Unit
    ) {
        if (!creationSession.isCurrent() || _isCreating.value) return
        val recoveryState = recovery.state.value
        if (recoveryState.loading || recoveryState.readFailed) return
        val pending = recoveryState.pending
        if (isPreparingImage.value) { _error.value = "Espera a que terminemos de preparar el icono."; return }
        if (pending == null) {
            val invalid = runCatching { conectate.com.data.repository.collection.requireCollectionCreationDetails(name.trim(), description, category) }.exceptionOrNull()
            if (invalid != null) { _error.value = invalid.message; return }
        }

        val image = selectedImageBytes.value
        val owner = creationSession.accountId ?: run { _error.value = "Inicia sesión para continuar."; return }
        _isCreating.value = true
        _error.value = null
        creationSession.scope.launch {
            var confirmed = false
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
                    if (id.isNotBlank()) { confirmed = true; onSuccess(id) }
                    else _error.value = "No pudimos confirmar la comunidad. Reintenta."
                } else {
                    _error.value = if (result.exceptionOrNull() is conectate.com.data.repository.collection.CollectionCreationRejectedException)
                        "Esa creación ya no está disponible. Puedes comenzar otra."
                    else "No pudimos confirmar la comunidad. Reintenta para continuar con la misma creación."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (creationSession.isCurrent()) _error.value = "No pudimos completar la operación. Conservamos tus datos para reintentar."
            } finally {
                try { if (!confirmed && creationSession.isCurrent()) recovery.refresh() }
                finally { _isCreating.value = false }
            }
        }
    }
    override fun onCleared() { creationSession.close(); super.onCleared() }

    init {
        creationSession.scope.launch { recovery.refresh() }
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
