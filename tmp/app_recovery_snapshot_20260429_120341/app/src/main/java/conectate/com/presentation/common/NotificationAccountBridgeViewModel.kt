package conectate.com.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.MultiAccountInboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class NotificationAccountBridgeViewModel  constructor(
    private val authRepository: AuthRepository,
    private val multiAccountInboxRepository: MultiAccountInboxRepository
) : ViewModel() {

    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _completedRoute = MutableStateFlow<String?>(null)
    val completedRoute: StateFlow<String?> = _completedRoute.asStateFlow()

    fun continueWithAccount(accountId: String, destinationRoute: String) {
        val cleanAccountId = accountId.trim()
        val cleanRoute = destinationRoute.trim()
        if (cleanAccountId.isBlank() || cleanRoute.isBlank() || _isSwitching.value) return

        viewModelScope.launch {
            _isSwitching.value = true
            val result = authRepository.switchAccount(cleanAccountId)
            _isSwitching.value = false

            result.onSuccess {
                multiAccountInboxRepository.acknowledgeAccount(cleanAccountId)
                _completedRoute.value = cleanRoute
            }.onFailure { error ->
                _errorMessage.value = if (looksLikeStoredAccountSessionIssue(error.message)) {
                    "Esta cuenta guardada vencio o dejo de ser valida. Inicia sesion manualmente con esa cuenta para volver a abrir este contenido."
                } else {
                    error.toUserFacingMessage()
                }
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun consumeCompletedRoute() {
        _completedRoute.value = null
    }
}






