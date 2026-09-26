package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.MultiAccountInboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MessageAccountsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val inbox: MultiAccountInboxRepository
) : ViewModel() {
    val accounts = inbox.accountSummaries
    val switching = authRepository.accountSwitchUiState
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _completed = MutableStateFlow(0L)
    val completed = _completed.asStateFlow()

    fun clearError() { _error.value = null }

    fun switchAccount(id: String) {
        if (switching.value.isSwitching || accounts.value.none { it.account.id == id && !it.isActive }) return
        viewModelScope.launch {
            _error.value = null
            try {
                authRepository.switchAccount(id).getOrThrow()
                inbox.acknowledgeAccount(id)
                _completed.value++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = error.message?.takeIf { it.isNotBlank() }
                    ?: "No se pudo cambiar de cuenta. Vuelve a intentarlo."
            }
        }
    }
}
