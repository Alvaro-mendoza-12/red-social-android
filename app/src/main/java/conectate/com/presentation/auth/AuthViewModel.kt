package conectate.com.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
// Phone auth now uses Supabase OTP flow (no Firebase PhoneAuthCredential)
import conectate.com.data.auth.AccountSuspendedException
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.model.User
import conectate.com.data.repository.AuthRepository
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
                val hasValidUser = authRepository.getCurrentUserId() != null
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

            val result = authRepository.signUpWithEmail(
                email, password, name, country, imageUri, acceptedPrivacyPolicy
            )

            _authState.value = result.fold(
                onSuccess = { 
                    // Don't auto-login, just show success and redirect to login
                    AuthState.Success(it) 
                },
                onFailure = { 
                    val message = mapAuthError(it)
                    AuthState.Error(message) 
                }
            )
        }
    }


    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            
            val result = authRepository.signInWithEmail(email, password)
            _authState.value = result.fold(
                onSuccess = {
                    AuthState.Success(it)
                },
                onFailure = {
                    when (it) {
                        is AccountSuspendedException -> AuthState.Suspended(
                            suspensionEndDate = it.suspensionEndDate,
                            reason = it.reason
                        )
                        else -> {
                            val message = mapAuthError(it)
                            AuthState.Error(message)
                        }
                    }
                }
            )
        }
    }

    private fun mapAuthError(throwable: Throwable): String {
        if (throwable is AccountSuspendedException) {
            return throwable.message
        }
        val message = throwable.message ?: ""
        return when {
            message.contains(AccountSuspendedException.ERROR_CODE, ignoreCase = true) ->
                "Tu cuenta está suspendida temporalmente."
            message.contains("suspendida", ignoreCase = true) ||
                message.contains("inhabilitada", ignoreCase = true) -> message
            message.contains("Invalid login credentials", ignoreCase = true) -> "Correo o contraseña incorrectos"
            message.contains("User not found", ignoreCase = true) -> "El usuario no existe"
            message.contains("Email not confirmed", ignoreCase = true) -> "Por favor confirma tu correo electrónico"
            message.contains("Signup requires a valid password", ignoreCase = true) -> "La contraseña no es válida"
            message.contains("User already registered", ignoreCase = true) -> "Este correo ya está registrado"
            message.contains("Network error", ignoreCase = true) -> "Error de conexión. Revisa tu internet"
            else -> "Ocurrió un error: ${throwable.localizedMessage ?: "Inténtalo de nuevo"}"
        }
    }

    // Start phone sign-in (requests OTP)
    fun requestPhoneOtp(phone: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepository.startPhoneSignIn(phone)
            _authState.value = result.fold(
                onSuccess = { AuthState.Idle },
                onFailure = { AuthState.Error(mapAuthError(it)) }
            )
        }
    }

    // Confirm phone OTP and complete sign-in/registration
    fun confirmPhoneOtp(phone: String, otp: String, name: String, country: String, city: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepository.confirmPhoneOtp(phone, otp, name, null)
            _authState.value = result.fold(
                onSuccess = { AuthState.Success(it) },
                onFailure = { AuthState.Error(mapAuthError(it)) }
            )
        }
    }

    suspend fun signOut() {
        authRepository.signOut()
        _isUserLoggedIn.value = false
        _authState.value = AuthState.Idle
    }

    fun saveCurrentAccount() {
        viewModelScope.launch {
            val result = authRepository.rememberCurrentAccount()
            _accountSwitchMessage.value = if (result.isSuccess) {
                "Cuenta guardada para cambio rápido."
            } else {
                "No se pudo guardar la cuenta."
            }
        }
    }

    fun switchAccount(accountId: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepository.switchAccount(accountId)
            _authState.value = result.fold(
                onSuccess = {
                    _accountSwitchMessage.value = "Cuenta cambiada correctamente."
                    AuthState.Success(it)
                },
                onFailure = {
                    _accountSwitchMessage.value = "No se pudo cambiar de cuenta."
                    AuthState.Error(mapAuthError(it))
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
            val result = authRepository.resetPassword(email)
            _authState.value = result.fold(
                onSuccess = { AuthState.PasswordResetSent },
                onFailure = { 
                    val message = mapAuthError(it)
                    AuthState.Error(message) 
                }
            )
        }
    }

    fun updatePassword(newPassword: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepository.updatePassword(newPassword)
            _authState.value = result.fold(
                onSuccess = { AuthState.PasswordChanged },
                onFailure = { 
                    val message = mapAuthError(it)
                    AuthState.Error(message) 
                }
            )
        }
    }
}
