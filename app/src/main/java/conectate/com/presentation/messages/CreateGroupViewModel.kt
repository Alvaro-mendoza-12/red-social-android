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
    private val authService: AuthService
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
    val selectedImageBytes = creationSession.selectedImageBytes
    val isPreparingImage = creationSession.preparingImage
    val sessionCurrent = creationSession.sessionCurrent
    fun isSessionCurrent() = creationSession.isCurrent()
    val ownerAccountId get() = creationSession.accountId
    fun prepareImage(loader: suspend () -> ByteArray?) {
        if (!_isCreating.value) creationSession.prepareImage(loader)
    }

    private val _currentStep = MutableStateFlow(GroupCreationStep.SELECT_MEMBERS)
    val currentStep: StateFlow<GroupCreationStep> = _currentStep.asStateFlow()

    private var searchJob: Job? = null
    private var searchRevision = 0L

    fun setImageBytes(bytes: ByteArray?) {
        if (!_isCreating.value) creationSession.setImageBytes(bytes)
    }

    fun searchUsers(query: String) {
        searchJob?.cancel()
        if (!creationSession.isCurrent()) return
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
        if (!creationSession.isCurrent() || _isCreating.value) return
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
        if (!creationSession.isCurrent() || _isCreating.value) return
        if (_selectedUsers.value.isNotEmpty()) {
            _currentStep.value = GroupCreationStep.ENTER_DETAILS
        } else {
            _errorMessage.value = "Selecciona al menos un participante"
        }
    }

    fun previousStep() {
        if (!creationSession.isCurrent() || _isCreating.value) return
        _currentStep.value = GroupCreationStep.SELECT_MEMBERS
    }

    fun createGroup(name: String, description: String?, onSuccess: (String) -> Unit) {
        if (!creationSession.isCurrent() || _isCreating.value) return
        if (isPreparingImage.value) { _errorMessage.value = "Espera a que terminemos de preparar el icono."; return }
        if (name.isBlank()) { _errorMessage.value = "El nombre es obligatorio"; return }
        val owner = creationSession.accountId ?: run { _errorMessage.value = "Inicia sesión para continuar."; return }
        val image = selectedImageBytes.value
        val participants = _selectedUsers.value.map { it.id }
        if (participants.isEmpty()) {
            _errorMessage.value = "Selecciona al menos un participante"
            return
        }

        _isCreating.value = true
        _errorMessage.value = null
        creationSession.scope.launch {
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
                         onSuccess(groupId)
                     } else _errorMessage.value = "No pudimos confirmar el grupo. Reintenta."
                } else {
                    _errorMessage.value = "No pudimos crear el grupo. Conservamos tus datos para reintentar."
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                if (creationSession.isCurrent()) _errorMessage.value = "No pudimos completar la operación. Conservamos tus datos para reintentar."
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
                    _selectedUsers.value = emptyList()
                    _searchResults.value = emptyList()
                    _isSearching.value = false
                }
            }
        }
    }

}
