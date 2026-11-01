package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.User
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

enum class GroupCreationStep {
    SELECT_MEMBERS,
    ENTER_DETAILS
}

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val groupRepository: conectate.com.data.repository.GroupRepository,
    private val userRepository: UserRepository,
    private val authService: AuthService,
    private val creationJournal: conectate.com.data.repository.collection.CollectionCreationJournal,
) : ViewModel() {

    private val _searchResults = MutableStateFlow<List<User>>(emptyList())
    val searchResults: StateFlow<List<User>> = _searchResults.asStateFlow()

    private val _selectedUsers = MutableStateFlow<List<User>>(emptyList())
    val selectedUsers: StateFlow<List<User>> = _selectedUsers.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val creationSession = CollectionCreationSession(viewModelScope, authService.captureAccountLease()) {
        _errorMessage.value = it
    }
    private val recovery = CollectionCreationRecovery(creationSession, {
        creationSession.accountId?.takeIf { authService.isLocalFallbackSession() }?.let { creationJournal.pending(it, "group") }
    }) { _errorMessage.value = it }
    internal val recoveryState = recovery.state
    fun retryRecovery() { if (!_isCreating.value) creationSession.scope.launch { recovery.refresh() } }
    fun retryPending(onSuccess: (String) -> Unit) {
        val pending = recovery.state.value.pending ?: return
        createGroup(pending.name, pending.description, onSuccess)
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

    private val _currentStep = MutableStateFlow(GroupCreationStep.SELECT_MEMBERS)
    val currentStep: StateFlow<GroupCreationStep> = _currentStep.asStateFlow()

    private var searchJob: Job? = null
    private var searchRevision = 0L

    fun setImageBytes(bytes: ByteArray?) {
        if (editable()) creationSession.setImageBytes(bytes)
    }

    fun searchUsers(query: String) {
        searchJob?.cancel()
        if (!editable()) return
        val revision = ++searchRevision
        _isSearching.value = false
        _searchResults.value = emptyList()
        _errorMessage.value = null
        if (query.isBlank()) {
            _searchResults.value = emptyList() // or load suggested
            return
        }

        searchJob = creationSession.scope.launch {
            _isSearching.value = true
            try {
                delay(350)
                // Delegate to UserRepo which should support searching users from Supabase
                val result = conectate.com.core.network.runReadWithDeadline(10_000) { userRepository.searchUsers(query.trim()).getOrThrow() }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                creationSession.requireCurrent()
                if (revision != searchRevision) return@launch
                if (result.isSuccess) {
                    _searchResults.value = result.getOrNull() ?: emptyList()
                } else {
                    _searchResults.value = emptyList()
                    _errorMessage.value = "No pudimos buscar participantes. Reintenta la búsqueda."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (creationSession.isCurrent() && revision == searchRevision) {
                    _searchResults.value = emptyList()
                    _errorMessage.value = "No pudimos buscar participantes. Reintenta la búsqueda."
                }
            } finally {
                if (revision == searchRevision) _isSearching.value = false
            }
        }
    }

    fun toggleUserSelection(user: User) {
        if (!editable()) return
        val current = _selectedUsers.value.toMutableList()
        if (current.any { it.id == user.id }) {
            current.removeAll { it.id == user.id }
        } else {
            current.add(user)
        }
        _selectedUsers.value = current
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun nextStep() {
        if (!editable()) return
        if (_selectedUsers.value.isNotEmpty()) {
            _currentStep.value = GroupCreationStep.ENTER_DETAILS
        } else {
            _errorMessage.value = "Selecciona al menos un participante"
        }
    }

    fun previousStep() {
        if (!editable()) return
        _currentStep.value = GroupCreationStep.SELECT_MEMBERS
    }

    fun createGroup(name: String, description: String?, onSuccess: (String) -> Unit) {
        if (!creationSession.isCurrent() || _isCreating.value) return
        val recoveryState = recovery.state.value
        if (recoveryState.loading || recoveryState.readFailed) return
        val pending = recoveryState.pending
        if (isPreparingImage.value) { _errorMessage.value = "Espera a que terminemos de preparar el icono."; return }
        if (pending == null) {
            val invalid = runCatching { conectate.com.data.repository.collection.requireCollectionCreationDetails(name.trim(), description) }.exceptionOrNull()
            if (invalid != null) { _errorMessage.value = invalid.message; return }
        }
        val owner = creationSession.accountId ?: run { _errorMessage.value = "Inicia sesión para continuar."; return }
        val image = selectedImageBytes.value
        val participants = pending?.memberIds ?: _selectedUsers.value.map { it.id }
        if (participants.isEmpty()) {
            _errorMessage.value = "Selecciona al menos un participante"
            return
        }

        _isCreating.value = true
        _errorMessage.value = null
        creationSession.scope.launch {
            var confirmed = false
            try {
                creationSession.requireCurrent()
                val result = groupRepository.createGroup(
                    name = name,
                    description = description,
                    imageBytes = image,
                    members = participants,
                    expectedAccountId = owner,
                )
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                creationSession.requireCurrent()
                if (result.isSuccess) {
                     val groupId = result.getOrNull()
                     if (!groupId.isNullOrBlank()) {
                         confirmed = true
                         onSuccess(groupId)
                     } else _errorMessage.value = "No pudimos confirmar el grupo. Reintenta."
                } else {
                    _errorMessage.value = if (result.exceptionOrNull() is conectate.com.data.repository.collection.CollectionCreationRejectedException)
                        "Esa creación ya no está disponible. Puedes comenzar otra."
                    else "No pudimos confirmar el grupo. Reintenta para continuar con la misma creación."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (creationSession.isCurrent()) _errorMessage.value = "No pudimos completar la operación. Conservamos tus datos para reintentar."
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
                    _selectedUsers.value = emptyList()
                    _searchResults.value = emptyList()
                    _isSearching.value = false
                }
            }
        }
    }

}
