package conectate.com.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.auth.AccountSuspendedException
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.model.User
import conectate.com.data.repository.AuthRepository
import conectate.com.presentation.common.looksLikeStoredAccountSessionIssue
import conectate.com.presentation.common.toUserFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class Success(val user: User) : AuthState()
    data class Suspended(val suspensionEndDate: String?, val reason: String?) : AuthState()
    object PasswordResetSent : AuthState()
    object PasswordChanged : AuthState()
    data class Error(val message: String) : AuthState()
}


@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _isUserLoggedIn = MutableStateFlow(
        authRepository.getCurrentUserId() != null
    )
    val isUserLoggedIn: StateFlow<Boolean> = _isUserLoggedIn.asStateFlow()
    private val _currentUserId = MutableStateFlow(authRepository.getCurrentUserId())
    val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    private val _storedAccounts = MutableStateFlow<List<StoredAccountSession>>(emptyList())
    val storedAccounts: StateFlow<List<StoredAccountSession>> = _storedAccounts.asStateFlow()

    private val _accountSwitchMessage = MutableStateFlow<String?>(null)
    val accountSwitchMessage: StateFlow<String?> = _accountSwitchMessage.asStateFlow()

    init {
        checkAuthState()
        observeStoredAccounts()
        validateSessionHealth()
    }

    private fun checkAuthState() {
        viewModelScope.launch {
            authRepository.getAuthStateFlow().collect { user ->
                val resolvedUserId = authRepository.getCurrentUserId()
                val hasValidUser = resolvedUserId != null
                _currentUserId.value = resolvedUserId
                _isUserLoggedIn.value = hasValidUser || user != null
            }
        }
    }

    private fun observeStoredAccounts() {
        viewModelScope.launch {
            authRepository.storedAccounts.collect { accounts ->
                _storedAccounts.value = accounts.sortedByDescending { it.last_used_at }
            }
        }
    }

    private fun validateSessionHealth() {
        viewModelScope.launch {
            if (!authRepository.isUserLoggedIn || authRepository.getCurrentUserId() == null) return@launch
            authRepository.enforceCurrentSessionIsActive()
                .onFailure { throwable ->
                    _authState.value = when (throwable) {
                        is AccountSuspendedException -> AuthState.Suspended(
                            suspensionEndDate = throwable.suspensionEndDate,
                            reason = throwable.reason
                        )
                        else -> AuthState.Error(mapAuthError(throwable))
                    }
                }
        }
    }

    fun signUpWithEmail(
        email: String,
        password: String,
        name: String,
        country: String,
        imageUri: Uri?,
        acceptedPrivacyPolicy: Boolean
    ) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading

            var result = authRepository.signUpWithEmail(
                email, password, name, country, imageUri, acceptedPrivacyPolicy
            )

            _authState.value = result.fold(
                onSuccess = { 
                    _isUserLoggedIn.value = true
                    _currentUserId.value = it.id
                    // Successful sign-up keeps the session active so the nav graph can open Home directly.
                    AuthState.Success(it) 
                },
                onFailure = { 
                    var message = mapAuthError(it)
                    AuthState.Error(message) 
                }
            )
        }
    }


    fun signInWithEmail(
        email: String,
        password: String,
        twoFactorCode: String? = null,
        backupCode: String? = null
    ) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            
            val result = if (!twoFactorCode.isNullOrBlank() || !backupCode.isNullOrBlank()) {
                authRepository.signInWithEmail2FA(
                    email = email,
                    password = password,
                    totpCode = twoFactorCode,
                    backupCode = backupCode
                )
            } else {
                authRepository.signInWithEmail(email, password)
            }
            _authState.value = result.fold(
                onSuccess = {
                    _isUserLoggedIn.value = true
                    _currentUserId.value = it.id
                    AuthState.Success(it)
                },
                onFailure = {
                    when (it) {
                        is AccountSuspendedException -> AuthState.Suspended(
                            suspensionEndDate = it.suspensionEndDate,
                            reason = it.reason
                        )
                        else -> {
                            var message = mapAuthError(it)
                            AuthState.Error(message)
                        }
                    }
                }
            )
        }
    }

    private fun mapAuthError(throwable: Throwable): String {
        return throwable.toUserFacingMessage()
    }

    private fun mapAccountSwitchError(throwable: Throwable): String {
        return if (looksLikeStoredAccountSessionIssue(throwable.message)) {
            "Esta cuenta guardada venció o dejó de ser válida. Inicia sesión manualmente con esa cuenta una vez para volver a activarla."
        } else {
            mapAuthError(throwable)
        }
    }

    suspend fun signOut() {
        authRepository.signOut()
        _isUserLoggedIn.value = false
        _currentUserId.value = null
        _authState.value = AuthState.Idle
    }

    /**
     * Immediate (non-suspending) logout for UI buttons.
     * Ensures we flip the app to "logged out" state before any navigation happens.
     */
    fun forceLocalLogout() {
        runCatching { authRepository.forceLocalLogout() }
            .onFailure { android.util.Log.e("AuthViewModel", "forceLocalLogout failed", it) }
        _isUserLoggedIn.value = false
        _currentUserId.value = null
        _authState.value = AuthState.Idle
    }

    fun saveCurrentAccount() {
        viewModelScope.launch {
            var result = authRepository.rememberCurrentAccount()
            _accountSwitchMessage.value = if (result.isSuccess) {
                "Cuenta guardada y actualizada para cambio rápido."
            } else {
                result.exceptionOrNull().toUserFacingMessage("No se pudo guardar la cuenta actual.")
            }
        }
    }

    fun switchAccount(accountId: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            var result = authRepository.switchAccount(accountId)
            _authState.value = result.fold(
                onSuccess = {
                    _isUserLoggedIn.value = true
                    _currentUserId.value = it.id
                    _accountSwitchMessage.value = "Cuenta cambiada correctamente."
                    AuthState.Success(it)
                },
                onFailure = {
                    val invalidStoredSession = looksLikeStoredAccountSessionIssue(it.message)
                    if (invalidStoredSession) {
                        authRepository.removeStoredAccount(accountId)
                    }
                    val friendlyMessage = if (invalidStoredSession) {
                        "Esta cuenta guardada venció o dejó de ser válida. La quitamos de la lista para evitar errores. Inicia sesión manualmente con esa cuenta para volver a guardarla."
                    } else {
                        mapAccountSwitchError(it)
                    }
                    _accountSwitchMessage.value = friendlyMessage
                    when (it) {
                        is AccountSuspendedException -> AuthState.Suspended(
                            suspensionEndDate = it.suspensionEndDate,
                            reason = it.reason
                        )
                        else -> AuthState.Idle
                    }
                }
            )
        }
    }

    fun removeStoredAccount(accountId: String) {
        authRepository.removeStoredAccount(accountId)
    }

    fun clearAccountSwitchMessage() {
        _accountSwitchMessage.value = null
    }

    fun resetAuthState() {
        _authState.value = AuthState.Idle
    }

    fun setAuthError(message: String) {
        _authState.value = AuthState.Error(message)
    }

    fun resetPassword(email: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            var result = authRepository.resetPassword(email)
            _authState.value = result.fold(
                onSuccess = { AuthState.PasswordResetSent },
                onFailure = { 
                    var message = mapAuthError(it)
                    AuthState.Error(message) 
                }
            )
        }
    }

    fun updatePassword(newPassword: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            var result = authRepository.updatePassword(newPassword)
            _authState.value = result.fold(
                onSuccess = { AuthState.PasswordChanged },
                onFailure = { 
                    var message = mapAuthError(it)
                    AuthState.Error(message) 
                }
            )
        }
    }
}







