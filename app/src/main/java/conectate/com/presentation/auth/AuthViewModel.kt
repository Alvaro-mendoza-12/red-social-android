package conectate.com.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.auth.AccountSuspendedException
import conectate.com.data.localfallback.LocalFallbackMigratedPasswordResetRequiredException
import conectate.com.data.localfallback.LocalFallbackMigratedPasswordSetupRequiredException
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
    data class Suspended(
        val suspensionEndDate: String?,
        val reason: String?,
        val appealToken: String? = null,
        val decisionId: String? = null,
        val ruleTitle: String? = null,
        val explanation: String? = null,
        val evidence: List<String> = emptyList(),
        val appealDeadline: String? = null,
        val appealStatus: String? = null,
        val appealReviewerNote: String? = null
    ) : AuthState()
    data class MigratedPasswordSetupRequired(
        val email: String,
        val displayName: String? = null,
        val migrationToken: String? = null
    ) : AuthState()
    data class MigrationPasswordCodeSent(
        val email: String,
        val resetToken: String? = null,
        val delivery: String? = null,
        val expiresInMinutes: Int? = null
    ) : AuthState()
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
    private val _appealSubmitting = MutableStateFlow(false)
    val appealSubmitting: StateFlow<Boolean> = _appealSubmitting.asStateFlow()
    private val _appealMessage = MutableStateFlow<String?>(null)
    val appealMessage: StateFlow<String?> = _appealMessage.asStateFlow()

    private fun suspendedState(error: AccountSuspendedException) = AuthState.Suspended(
        suspensionEndDate = error.suspensionEndDate,
        reason = error.reason,
        appealToken = error.appealToken,
        decisionId = error.decisionId,
        ruleTitle = error.ruleTitle,
        explanation = error.explanation,
        evidence = error.evidence,
        appealDeadline = error.appealDeadline,
        appealStatus = error.appealStatus,
        appealReviewerNote = error.appealReviewerNote
    )

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
            if (!authRepository.isUserLoggedIn || authRepository.getCurrentUserId() == null) {
                return@launch
            }
            authRepository.enforceCurrentSessionIsActive()
                .onFailure { throwable ->
                    _authState.value = when (throwable) {
                        is AccountSuspendedException -> suspendedState(throwable)
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


    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _appealMessage.value = null
            _authState.value = AuthState.Loading
            
            var result = authRepository.signInWithEmail(email, password)
            _authState.value = result.fold(
                onSuccess = {
                    _isUserLoggedIn.value = true
                    _currentUserId.value = it.id
                    AuthState.Success(it)
                },
                onFailure = {
                    when (it) {
                        is AccountSuspendedException -> suspendedState(it)
                        is LocalFallbackMigratedPasswordSetupRequiredException ->
                            AuthState.MigratedPasswordSetupRequired(
                                email = it.email.ifBlank { email.trim().lowercase() },
                                displayName = it.displayName,
                                migrationToken = it.migrationToken
                            )
                        is LocalFallbackMigratedPasswordResetRequiredException ->
                            AuthState.MigratedPasswordSetupRequired(
                                email = it.email.ifBlank { email.trim().lowercase() },
                                displayName = it.displayName,
                                migrationToken = it.migrationToken
                            )
                        else -> {
                            AuthState.Error(mapAuthError(it))
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

    fun switchAccount(accountId: String, onSuccess: (User) -> Unit = {}) {
        if (_authState.value is AuthState.Loading) return
        _authState.value = AuthState.Loading
        viewModelScope.launch {
            val result = authRepository.switchAccount(accountId)
            result.fold(
                onSuccess = {
                    _isUserLoggedIn.value = true
                    _currentUserId.value = it.id
                    _accountSwitchMessage.value = "Cuenta cambiada correctamente."
                    _authState.value = AuthState.Success(it)
                    onSuccess(it)
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
                    _authState.value = when (it) {
                        is AccountSuspendedException -> suspendedState(it)
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
        _appealMessage.value = null
        _appealSubmitting.value = false
    }

    fun submitModerationAppeal(statement: String) {
        val suspended = _authState.value as? AuthState.Suspended ?: return
        val token = suspended.appealToken.orEmpty()
        val normalized = statement.trim()
        if (token.isBlank()) {
            _appealMessage.value = "Vuelve a iniciar sesión para obtener un permiso seguro de apelación."
            return
        }
        if (normalized.length < 20) {
            _appealMessage.value = "Explica tu apelación con al menos 20 caracteres."
            return
        }
        if (_appealSubmitting.value) return
        viewModelScope.launch {
            _appealSubmitting.value = true
            _appealMessage.value = null
            authRepository.submitModerationAppeal(token, normalized)
                .onSuccess {
                    _authState.value = suspended.copy(appealStatus = "pending")
                    _appealMessage.value =
                        "Apelación enviada. Un administrador la revisará y podrás consultar el resultado al volver a iniciar sesión."
                }
                .onFailure {
                    _appealMessage.value = mapAuthError(it)
                }
            _appealSubmitting.value = false
        }
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

    fun requestMigratedPasswordCode(email: String) {
        viewModelScope.launch {
            val normalizedEmail = email.trim().lowercase()
            if (normalizedEmail.isBlank()) {
                _authState.value = AuthState.Error("Ingresa el correo de la cuenta migrada.")
                return@launch
            }

            _authState.value = AuthState.Loading
            val result = authRepository.requestLocalFallbackPasswordReset(normalizedEmail)
            _authState.value = result.fold(
                onSuccess = {
                    AuthState.MigrationPasswordCodeSent(
                        email = normalizedEmail,
                        resetToken = it.resetToken,
                        delivery = it.delivery,
                        expiresInMinutes = it.expiresInMinutes
                    )
                },
                onFailure = {
                    AuthState.Error(mapAuthError(it))
                }
            )
        }
    }

    fun confirmMigratedPasswordCode(resetToken: String, newPassword: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepository.confirmLocalFallbackPasswordReset(resetToken, newPassword)
            _authState.value = result.fold(
                onSuccess = {
                    _isUserLoggedIn.value = true
                    _currentUserId.value = it.id
                    AuthState.Success(it)
                },
                onFailure = {
                    AuthState.Error(mapAuthError(it))
                }
            )
        }
    }

    fun completeMigratedPasswordReset(migrationToken: String, newPassword: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepository.completeMigratedLocalPasswordReset(migrationToken, newPassword)
            _authState.value = result.fold(
                onSuccess = {
                    _isUserLoggedIn.value = true
                    _currentUserId.value = it.id
                    AuthState.Success(it)
                },
                onFailure = {
                    AuthState.Error(mapAuthError(it))
                }
            )
        }
    }

    /**
     * Finaliza la migración únicamente con el permiso firmado que el servidor
     * entrega después de comprobar la contraseña anterior de la cuenta.
     */
    fun setupMigratedPassword(email: String, migrationToken: String?, newPassword: String) {
        viewModelScope.launch {
            val normalizedEmail = email.trim().lowercase()
            val token = migrationToken?.trim().orEmpty()
            val nextPassword = newPassword.trim()

            if (normalizedEmail.isBlank()) {
                _authState.value = AuthState.Error("No se encontró el correo de la cuenta migrada.")
                return@launch
            }
            if (nextPassword.length < 8) {
                _authState.value = AuthState.Error("La nueva contraseña debe tener al menos 8 caracteres.")
                return@launch
            }

            _authState.value = AuthState.Loading

            if (token.isNotBlank()) {
                val result = authRepository.completeMigratedLocalPasswordReset(token, nextPassword)
                _authState.value = result.fold(
                    onSuccess = {
                        _isUserLoggedIn.value = true
                        _currentUserId.value = it.id
                        AuthState.Success(it)
                    },
                    onFailure = { AuthState.Error(mapAuthError(it)) }
                )
                return@launch
            }

            _authState.value = AuthState.Error(
                "Primero vuelve al inicio, escribe tu contraseña actual y toca Entrar para autorizar el cambio."
            )
        }
    }

    /**
     * Flujo directo sin código de migración para usuarios migrados:
     * Solo requiere email y nueva contraseña (con verificación).
     */
    fun directPasswordReset(email: String, newPassword: String, confirmPassword: String) {
        viewModelScope.launch {
            val normalizedEmail = email.trim().lowercase()
            val nextPassword = newPassword.trim()
            val nextConfirmPassword = confirmPassword.trim()

            if (normalizedEmail.isBlank()) {
                _authState.value = AuthState.Error("Por favor, ingresa tu correo electrónico.")
                return@launch
            }
            if (nextPassword.length < 8) {
                _authState.value = AuthState.Error("La nueva contraseña debe tener al menos 8 caracteres.")
                return@launch
            }
            if (nextPassword != nextConfirmPassword) {
                _authState.value = AuthState.Error("Las contraseñas no coinciden.")
                return@launch
            }

            _authState.value = AuthState.Loading
            val result = authRepository.directPasswordReset(normalizedEmail, nextPassword)
            _authState.value = result.fold(
                onSuccess = {
                    _isUserLoggedIn.value = true
                    _currentUserId.value = it.id
                    AuthState.Success(it)
                },
                onFailure = { AuthState.Error(mapAuthError(it)) }
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







