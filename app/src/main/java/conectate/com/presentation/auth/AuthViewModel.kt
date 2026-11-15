package conectate.com.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.auth.AccountSuspendedException
import conectate.com.data.localfallback.LocalFallbackMigratedPasswordResetRequiredException
import conectate.com.data.localfallback.LocalFallbackMigratedPasswordSetupRequiredException
import conectate.com.data.localfallback.LocalFallbackTwoFactorRequiredException
import conectate.com.data.localfallback.LocalFallbackPasswordChangedNeedsTwoFactorException
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.GuestModeApi
import conectate.com.data.localfallback.UsernameAvailabilityResponse
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.model.User
import conectate.com.data.repository.AuthRepository
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.presentation.common.looksLikeStoredAccountSessionIssue
import conectate.com.presentation.common.toUserFacingMessage
import conectate.com.core.concurrency.AccountTasks
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    data class TwoFactorRequired(val message: String) : AuthState()
    object PasswordChangedNeedsTwoFactor : AuthState()
    data class Error(val message: String) : AuthState()
}


@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val localFallbackApiClient: LocalFallbackApiClient,
    private val guestModeApi: GuestModeApi,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val emailRecoveryStore = EmailRecoveryDraftStore(context)
    private var emailRecoveryVisit = emailRecoveryStore.currentVisit()
    private val emailRecoveryTasks = AccountTasks(viewModelScope)
    private var emailRecoveryBusyGeneration: Int? = null
    private val authEntryTasks = AccountTasks(viewModelScope)
    private val accountEntryTasks = AccountTasks(viewModelScope)
    private var accountEntryGeneration = 0L
    private var accountEntryOwner: AuthSessionLease? = null
    private var accountEntryScreenOwner: Any? = null
    private val emailRecoveryMutex = Mutex()
    private var emailRecoveryGeneration = 0
    private data class PendingEmailRecoveryClear(val generation: Int, val draft: EmailRecoveryDraft?)
    private var pendingEmailRecoveryClear: PendingEmailRecoveryClear? = null
    private val _emailRecovery = MutableStateFlow<EmailRecoveryDraft?>(null)
    val emailRecovery: StateFlow<EmailRecoveryDraft?> = _emailRecovery.asStateFlow()

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
    private var preparingAddAccount = false
    val accountSwitchMessage: StateFlow<String?> = _accountSwitchMessage.asStateFlow()
    private val _appealSubmitting = MutableStateFlow(false)
    private var appealGeneration = 0L
    val appealSubmitting: StateFlow<Boolean> = _appealSubmitting.asStateFlow()
    private val _appealMessage = MutableStateFlow<String?>(null)
    val appealMessage: StateFlow<String?> = _appealMessage.asStateFlow()
    private val _assistedRecoverySubmitting = MutableStateFlow(false)
    private var assistedRecoveryGeneration = 0L
    val assistedRecoverySubmitting: StateFlow<Boolean> = _assistedRecoverySubmitting.asStateFlow()
    private val _assistedRecoveryMessage = MutableStateFlow<String?>(null)
    val assistedRecoveryMessage: StateFlow<String?> = _assistedRecoveryMessage.asStateFlow()
    private val _assistedRecoveryCaseId = MutableStateFlow<String?>(
        context.getSharedPreferences("recovery_request", Context.MODE_PRIVATE).getString("case_id", null)
    )
    val assistedRecoveryCaseId: StateFlow<String?> = _assistedRecoveryCaseId.asStateFlow()

    private fun resetEntryOperations() {
        // Invalidate old completions before cancelling. A queued coroutine may
        // never enter its finally; publish idle only after its scope is replaced.
        resetEmailRecoveryOperations()
        assistedRecoveryGeneration += 1
        authEntryTasks.reset()
        _assistedRecoverySubmitting.value = false
        cancelAccountEntryOperations()
    }

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
        // A queued restoration belongs to construction, not to a later clear.
        val generation = emailRecoveryGeneration
        val visit = emailRecoveryVisit
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { emailRecoveryStore.read() }
            if (generation == emailRecoveryGeneration && visit.isCurrent()) _emailRecovery.value = restored
        }
        checkAuthState()
        observeStoredAccounts()
        observeEmailRecoveryVisits()
        validateSessionHealth()
    }

    private fun checkAuthState() {
        viewModelScope.launch {
            authRepository.getAuthStateFlow().collect { user ->
                val resolvedUserId = authRepository.getCurrentUserId()
                cancelStaleAccountEntries()
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

    private fun resetEmailRecoveryOperations() {
        emailRecoveryGeneration += 1
        emailRecoveryBusyGeneration = null
        pendingEmailRecoveryClear = null
        emailRecoveryTasks.reset()
    }

    private fun discardStaleEmailRecovery() {
        if (emailRecoveryVisit.isCurrent()) return
        val wasLoading = emailRecoveryBusyGeneration != null && _authState.value is AuthState.Loading
        resetEmailRecoveryOperations()
        _emailRecovery.value = null
        if (wasLoading) _authState.value = AuthState.Error("La recuperación cambió en otra pantalla. Vuelve a abrirla para continuar.")
    }

    private fun observeEmailRecoveryVisits() {
        viewModelScope.launch { emailRecoveryStore.visitChanges.collect { discardStaleEmailRecovery() } }
    }

    private fun resumeEmailRecoveryVisit(): EmailRecoveryVisit? = try {
        emailRecoveryStore.resumeWriteVisit(emailRecoveryVisit) { emailRecoveryVisit = it }
    } catch (cancelled: CancellationException) {
        discardStaleEmailRecovery()
        _authState.value = AuthState.Error("La recuperación cambió en otra pantalla. Vuelve a abrirla para continuar.")
        null
    }

    private fun emailRecoveryIsCurrent(generation: Int, visit: EmailRecoveryVisit, snapshot: EmailRecoveryDraft?): Boolean =
        generation == emailRecoveryGeneration && visit.isCurrent() && _emailRecovery.value == snapshot

    private fun launchEmailRecovery(generation: Int, visit: EmailRecoveryVisit, action: suspend () -> Unit) {
        emailRecoveryBusyGeneration = generation
        emailRecoveryTasks.scope.launch {
            try { action() } finally {
                if (emailRecoveryBusyGeneration == generation) {
                    emailRecoveryBusyGeneration = null
                    if (generation == emailRecoveryGeneration && !visit.isCurrent() && _authState.value is AuthState.Loading)
                        _authState.value = AuthState.Error("La recuperación cambió en otra pantalla. Vuelve a abrirla para continuar.")
                }
            }
        }
    }

    private fun validateSessionHealth() {
        viewModelScope.launch {
            if (!authRepository.isUserLoggedIn || authRepository.getCurrentUserId() == null) {
                return@launch
            }
            authEntryOperation { authRepository.enforceCurrentSessionIsActive() }
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
        username: String,
        country: String,
        gender: String,
        birthDate: String,
        imageUri: Uri?,
        acceptedPrivacyPolicy: Boolean
    ) {
        if (_authState.value is AuthState.Loading) return
        _authState.value = AuthState.Loading
        authEntryTasks.scope.launch {

            val result = authEntryOperation { authRepository.signUpWithEmail(
                email, password, name, username, country, gender, birthDate, imageUri, acceptedPrivacyPolicy
            ) }

            _authState.value = result.fold(
                onSuccess = { 
                    guestModeApi.clear()
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


    fun signInWithEmail(email: String, password: String, secondFactorCode: String? = null) {
        if (_authState.value is AuthState.Loading) return
        _appealMessage.value = null
        _authState.value = AuthState.Loading
        authEntryTasks.scope.launch {
            val result = authEntryOperation { authRepository.signInWithEmail(email, password, secondFactorCode) }
            _authState.value = result.fold(
                onSuccess = {
                    guestModeApi.clear()
                    _isUserLoggedIn.value = true
                    _currentUserId.value = it.id
                    _assistedRecoveryCaseId.value = null
                    context.getSharedPreferences("recovery_request", Context.MODE_PRIVATE)
                        .edit().remove("case_id").apply()
                    AuthState.Success(it)
                },
                onFailure = {
                    when (it) {
                        is LocalFallbackTwoFactorRequiredException ->
                            AuthState.TwoFactorRequired(it.message.orEmpty())
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
        resetEntryOperations()
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
        resetEntryOperations()
        runCatching { authRepository.forceLocalLogout() }
            .onFailure { android.util.Log.e("AuthViewModel", "forceLocalLogout failed", it) }
        _isUserLoggedIn.value = false
        _currentUserId.value = null
        _authState.value = AuthState.Idle
    }

    /** Discard callbacks for the departing settings visit, retaining durable accounts. */
    fun cancelAccountEntryOperations(visitOwner: Any? = null) {
        if (visitOwner != null && accountEntryScreenOwner !== visitOwner) return
        accountEntryGeneration += 1
        accountEntryTasks.reset()
        accountEntryOwner = null
        accountEntryScreenOwner = null
        preparingAddAccount = false
        _accountSwitchMessage.value = null
    }

    private fun cancelStaleAccountEntries() {
        if (accountEntryOwner?.isCurrent() == false) cancelAccountEntryOperations()
    }

    private fun beginAccountEntry(visitOwner: Any?): AuthSessionLease {
        val owner = authRepository.captureAccountLease()
        if (accountEntryOwner?.sameVisit(owner) == false || accountEntryScreenOwner !== visitOwner) {
            cancelAccountEntryOperations()
        }
        accountEntryOwner = owner
        accountEntryScreenOwner = visitOwner
        return owner
    }

    private fun rejectStaleAccountEntry(owner: AuthSessionLease, generation: Long): Boolean {
        if (generation != accountEntryGeneration) return true
        if (owner.isCurrent()) return false
        // Only the expired current visit may reset its state. A completion from
        // an earlier generation cannot cancel a newly admitted preparation.
        cancelAccountEntryOperations()
        return true
    }

    fun saveCurrentAccount(visitOwner: Any? = null) {
        if (_authState.value is AuthState.Loading) return
        val owner = beginAccountEntry(visitOwner)
        val generation = accountEntryGeneration
        accountEntryTasks.scope.launch {
            if (rejectStaleAccountEntry(owner, generation)) return@launch
            val result = authEntryOperation {
                owner.requireCurrent()
                authRepository.rememberCurrentAccount()
            }
            if (rejectStaleAccountEntry(owner, generation)) return@launch
            _accountSwitchMessage.value = if (result.isSuccess) {
                "Cuenta guardada y actualizada para cambio rápido."
            } else {
                result.exceptionOrNull().toUserFacingMessage("No se pudo guardar la cuenta actual.")
            }
        }
    }

    suspend fun checkUsernameAvailability(username: String, displayName: String): Result<UsernameAvailabilityResponse> =
        localFallbackApiClient.checkUsernameAvailability(username, displayName)

    fun prepareAddAccount(visitOwner: Any? = null, onReady: () -> Unit) {
        if (_authState.value is AuthState.Loading) return
        val owner = beginAccountEntry(visitOwner)
        if (preparingAddAccount || !owner.isCurrent()) return
        val generation = accountEntryGeneration
        preparingAddAccount = true
        accountEntryTasks.scope.launch {
            val savingMessage = "Guardando la cuenta actual…"
            var replyHandled = false
            try {
                if (rejectStaleAccountEntry(owner, generation)) return@launch
                _accountSwitchMessage.value = savingMessage
                val result = authEntryOperation {
                    owner.requireCurrent()
                    authRepository.rememberCurrentAccount()
                }
                if (rejectStaleAccountEntry(owner, generation)) return@launch
                result.fold(
                    onSuccess = {
                        guestModeApi.clear()
                        _accountSwitchMessage.value = null
                        // A previous login success must not complete the add-account screen.
                        _authState.value = AuthState.Idle
                        onReady()
                    },
                    onFailure = {
                        _accountSwitchMessage.value = it.toUserFacingMessage("No se pudo guardar la cuenta actual.")
                    },
                )
                replyHandled = true
            } finally {
                if (generation == accountEntryGeneration) {
                    preparingAddAccount = false
                    if (!replyHandled && _accountSwitchMessage.value == savingMessage) {
                        _accountSwitchMessage.value = null
                    }
                }
            }
        }
    }

    fun switchAccount(accountId: String, onSuccess: (User) -> Unit = {}) {
        if (_authState.value is AuthState.Loading) return
        cancelAccountEntryOperations()
        _authState.value = AuthState.Loading
        authEntryTasks.scope.launch {
            val result = authEntryOperation { authRepository.switchAccount(accountId) }
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
        resetEntryOperations()
        appealGeneration += 1
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
        val generation = ++appealGeneration
        _appealSubmitting.value = true
        _appealMessage.value = null
        authEntryTasks.scope.launch {
            try {
                val result = authEntryOperation { authRepository.submitModerationAppeal(token, normalized) }
                if (generation != appealGeneration || _authState.value !== suspended) return@launch
                result.onSuccess {
                    _authState.value = suspended.copy(appealStatus = "pending")
                    _appealMessage.value =
                        "Apelación enviada. Un administrador la revisará y podrás consultar el resultado al volver a iniciar sesión."
                }.onFailure {
                    _appealMessage.value = mapAuthError(it)
                }
            } finally {
                if (generation == appealGeneration) _appealSubmitting.value = false
            }
        }
    }

    fun setAuthError(message: String) {
        _authState.value = AuthState.Error(message)
    }

    fun resetPassword(email: String) {
        if (_authState.value is AuthState.Loading) return
        _authState.value = AuthState.Loading
        authEntryTasks.scope.launch {
            _authState.value = AuthState.Loading
            val result = authEntryOperation { authRepository.resetPassword(email) }
            _authState.value = result.fold(
                onSuccess = { AuthState.PasswordResetSent },
                onFailure = { 
                    var message = mapAuthError(it)
                    AuthState.Error(message) 
                }
            )
        }
    }

    private suspend fun persistEmailRecovery(snapshot: EmailRecoveryDraft?, generation: Int, visit: EmailRecoveryVisit) {
        visit.requireCurrent()
        withContext(Dispatchers.IO) {
            emailRecoveryMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (!emailRecoveryIsCurrent(generation, visit, snapshot))
                    throw CancellationException("La recuperación cambió antes de guardar el avance.")
                if (snapshot == null) emailRecoveryStore.clearForVisit(visit)
                else emailRecoveryStore.saveForVisit(snapshot, visit)
            }
        }
        visit.requireCurrent()
    }

    fun clearEmailRecovery(onCleared: () -> Unit = {}) {
        if (!emailRecoveryVisit.isCurrent()) {
            // A new Back action may leave an obsolete entry, but cannot erase
            // the different recovery now owned by another screen.
            resetEntryOperations()
            _emailRecovery.value = null
            _authState.value = AuthState.Idle
            onCleared()
            return
        }
        val visit = resumeEmailRecoveryVisit() ?: return
        clearEmailRecoveryForVisit(visit, onCleared)
    }

    private fun clearEmailRecoveryForVisit(visit: EmailRecoveryVisit, onCleared: () -> Unit) {
        if (!visit.isCurrent()) return
        val previous = _emailRecovery.value ?: pendingEmailRecoveryClear
            ?.takeIf { it.generation == emailRecoveryGeneration }?.draft
        resetEntryOperations()
        val generation = emailRecoveryGeneration
        pendingEmailRecoveryClear = PendingEmailRecoveryClear(generation, previous)
        _emailRecovery.value = null
        _authState.value = AuthState.Loading
        launchEmailRecovery(generation, visit) {
            val result = authEntryOperation {
                persistEmailRecovery(null, generation, visit)
                Result.success(Unit)
            }
            if (!emailRecoveryIsCurrent(generation, visit, null)) return@launchEmailRecovery
            pendingEmailRecoveryClear = null
            result.fold(
                onSuccess = { _authState.value = AuthState.Idle; onCleared() },
                onFailure = {
                    _emailRecovery.value = previous
                    _authState.value = AuthState.Error("No se pudo eliminar el avance de recuperación. Inténtalo de nuevo antes de salir.")
                },
            )
        }
    }

    fun updateEmailRecoveryCode(value: String) {
        val previous = _emailRecovery.value ?: return
        if (previous.verified || _authState.value is AuthState.Loading) return
        val visit = resumeEmailRecoveryVisit() ?: return
        val generation = emailRecoveryGeneration
        val code = value.filter { it in '0'..'9' }.take(6)
        val next = previous.copy(code = code)
        _emailRecovery.value = next
        if (_authState.value is AuthState.Error) _authState.value = AuthState.Idle
        emailRecoveryTasks.scope.launch {
            val saved = authEntryOperation { persistEmailRecovery(next, generation, visit); Result.success(Unit) }
            if (emailRecoveryIsCurrent(generation, visit, next) && saved.isFailure && _authState.value !is AuthState.Loading)
                _authState.value = AuthState.Error("No se pudo guardar el avance. Mantén abierta esta pantalla para terminar.")
        }
        if (code.length == 6 && code != previous.code) verifyEmailRecoveryCode()
    }

    fun verifyEmailRecoveryCode() {
        val current = _emailRecovery.value ?: return
        if (current.code.length != 6 || current.verified || _authState.value is AuthState.Loading) return
        val visit = resumeEmailRecoveryVisit() ?: return
        val generation = emailRecoveryGeneration
        _authState.value = AuthState.Loading
        launchEmailRecovery(generation, visit) {
            val result = authEntryOperation { localFallbackApiClient.verifyEmailPasswordCode(current.email, current.code) }
            if (!emailRecoveryIsCurrent(generation, visit, current)) return@launchEmailRecovery
            result.fold(onSuccess = { verified ->
                val next = current.copy(code = "", resetToken = verified.resetToken.orEmpty(),
                    expiresAt = System.currentTimeMillis() + (verified.expiresInMinutes ?: 10) * 60_000L)
                _emailRecovery.value = next
                val persisted = authEntryOperation { persistEmailRecovery(next, generation, visit); Result.success(Unit) }
                if (!emailRecoveryIsCurrent(generation, visit, next)) return@launchEmailRecovery
                _authState.value = persisted.fold(
                    onSuccess = { AuthState.Idle },
                    onFailure = { AuthState.Error("No se pudo guardar el avance. Mantén abierta esta pantalla para terminar.") })
            }, onFailure = { _authState.value = AuthState.Error(mapAuthError(it)) })
        }
    }

    fun requestMigratedPasswordCode(email: String) {
        discardStaleEmailRecovery()
        if (_authState.value is AuthState.Loading || _emailRecovery.value?.verified == true) return
        val normalizedEmail = email.trim().lowercase()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches()) {
            _authState.value = AuthState.Error("Ingresa un correo válido de tu cuenta.")
            return
        }
        val previous = _emailRecovery.value
        val visit = emailRecoveryStore.beginWriteVisit { emailRecoveryVisit = it }
        resetEmailRecoveryOperations()
        val generation = emailRecoveryGeneration
        _authState.value = AuthState.Loading
        launchEmailRecovery(generation, visit) {
            val pending = EmailRecoveryDraft(normalizedEmail, expiresAt = System.currentTimeMillis() + 15 * 60_000L,
                resendAt = System.currentTimeMillis() + 60_000, deliveryConfirmed = false)
            _emailRecovery.value = pending
            var requestStarted = false
            val result = authEntryOperation {
                persistEmailRecovery(pending, generation, visit)
                requestStarted = true
                authRepository.requestLocalFallbackPasswordReset(normalizedEmail)
            }
            if (!emailRecoveryIsCurrent(generation, visit, pending)) return@launchEmailRecovery
            result.fold(onSuccess = { response ->
                if (response.delivery == "disabled") {
                    clearEmailRecoveryForVisit(visit) {
                        _authState.value = AuthState.Error("El envío por correo no está disponible. Usa la ayuda para recuperar tu cuenta.")
                    }
                } else if (response.codeLength == 6) {
                    val next = pending.copy(expiresAt = System.currentTimeMillis() + (response.expiresInMinutes ?: 15) * 60_000L,
                        deliveryConfirmed = true)
                    _emailRecovery.value = next
                    val persisted = authEntryOperation { persistEmailRecovery(next, generation, visit); Result.success(Unit) }
                    if (!emailRecoveryIsCurrent(generation, visit, next)) return@launchEmailRecovery
                    _authState.value = persisted.fold(
                        onSuccess = { AuthState.Idle },
                        onFailure = { AuthState.Error("No se pudo guardar el avance. Mantén abierta esta pantalla para terminar.") },
                    )
                } else {
                    clearEmailRecoveryForVisit(visit) {
                        _authState.value = AuthState.MigrationPasswordCodeSent(normalizedEmail, response.resetToken, response.delivery, response.expiresInMinutes)
                    }
                }
            }, onFailure = {
                if (requestStarted) {
                    val uncertain = pending.copy(resendAt = 0, deliveryConfirmed = false)
                    _emailRecovery.value = uncertain
                    authEntryOperation { persistEmailRecovery(uncertain, generation, visit); Result.success(Unit) }
                    if (!emailRecoveryIsCurrent(generation, visit, uncertain)) return@launchEmailRecovery
                    _authState.value = AuthState.Error("No pudimos confirmar el envío. Si llega el código, puedes usarlo aquí o volver a solicitarlo.")
                } else {
                    // No request was sent. Do not clear the preceding durable
                    // recovery merely because its replacement could not commit.
                    _emailRecovery.value = previous
                    _authState.value = AuthState.Error("No se pudo guardar el avance de recuperación en este dispositivo. Revisa el almacenamiento e inténtalo de nuevo.")
                }
            })
        }
    }

    private val _recoveryEvidence = MutableStateFlow<List<RecoveryEvidenceSelection>>(emptyList())
    val recoveryEvidence: StateFlow<List<RecoveryEvidenceSelection>> = _recoveryEvidence.asStateFlow()
    fun selectRecoveryEvidence(uris: List<Uri>) {
        if (_assistedRecoverySubmitting.value) return
        val nextUris = (_recoveryEvidence.value.map { it.uri } + uris).distinct()
        if (nextUris.size > 5) { _assistedRecoveryMessage.value = "Adjunta como máximo cinco archivos."; return }
        val operation = ++assistedRecoveryGeneration
        _assistedRecoverySubmitting.value = true
        authEntryTasks.scope.launch {
            try {
                val selection = withContext(Dispatchers.IO) { nextUris.map { readRecoveryEvidenceMetadata(context, it) } }
                if (operation != assistedRecoveryGeneration) return@launch
                _recoveryEvidence.value = selection
                _assistedRecoveryMessage.value = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (operation == assistedRecoveryGeneration)
                    _assistedRecoveryMessage.value = "No pudimos leer el archivo. Selecciona una copia disponible en este dispositivo."
            } finally {
                if (operation == assistedRecoveryGeneration) _assistedRecoverySubmitting.value = false
            }
        }
    }
    fun removeRecoveryEvidence(uri: Uri) { if (!_assistedRecoverySubmitting.value) _recoveryEvidence.value = _recoveryEvidence.value.filterNot { it.uri == uri } }

    fun requestAssistedRecovery(identifier: String, alternateContact: String, description: String, contactType: String, profileHint: String) {
        if (_assistedRecoverySubmitting.value) return
        val operation = ++assistedRecoveryGeneration
        _assistedRecoverySubmitting.value = true
        _assistedRecoveryMessage.value = null
        authEntryTasks.scope.launch {
            try {
                val result = authEntryOperation {
                    val files = withContext(Dispatchers.IO) { _recoveryEvidence.value.map { loadRecoveryEvidence(context, it) } }
                    localFallbackApiClient.requestAssistedRecovery(
                        identifier.trim(), alternateContact.trim(), description.trim(), contactType, profileHint.trim(), files
                    )
                }
                if (operation != assistedRecoveryGeneration) return@launch
                _assistedRecoveryMessage.value = result.fold(
                onSuccess = {
                    _assistedRecoveryCaseId.value = it.caseId
                    _recoveryEvidence.value = emptyList()
                    context.getSharedPreferences("recovery_request", Context.MODE_PRIVATE)
                        .edit().putString("case_id", it.caseId).apply()
                    "Solicitud recibida. Un administrador revisará la cuenta y, si confirma que te pertenece, enviará un código al contacto indicado para que crees una contraseña nueva."
                },
                onFailure = { mapAuthError(it) }
            )
            } finally {
                if (operation == assistedRecoveryGeneration) _assistedRecoverySubmitting.value = false
            }
        }
    }

    fun confirmAssistedContact(caseId: String, code: String) {
        if (_assistedRecoverySubmitting.value) return
        val operation = ++assistedRecoveryGeneration
        _assistedRecoverySubmitting.value = true
        authEntryTasks.scope.launch {
            try {
                val result = authEntryOperation {
                    localFallbackApiClient.confirmAssistedRecoveryContact(caseId.trim(), code.trim())
                }
                if (operation != assistedRecoveryGeneration) return@launch
                _assistedRecoveryMessage.value = result.fold(
                    onSuccess = { "Contacto confirmado. Un administrador revisará ahora las pruebas de propiedad de tu cuenta." },
                    onFailure = { mapAuthError(it) },
                )
            } finally {
                if (operation == assistedRecoveryGeneration) _assistedRecoverySubmitting.value = false
            }
        }
    }

    fun confirmMigratedPasswordCode(resetToken: String, newPassword: String) {
        if (_authState.value is AuthState.Loading) return
        val visit = resumeEmailRecoveryVisit() ?: return
        val operation = emailRecoveryGeneration
        _authState.value = AuthState.Loading
        launchEmailRecovery(operation, visit) {
            val result = authEntryOperation { authRepository.confirmLocalFallbackPasswordReset(resetToken, newPassword) }
            if (operation != emailRecoveryGeneration || !visit.isCurrent()) return@launchEmailRecovery
            _authState.value = result.fold(
                onSuccess = {
                    val generation = ++emailRecoveryGeneration
                    _emailRecovery.value = null
                    authEntryOperation { persistEmailRecovery(null, generation, visit); Result.success(Unit) }
                    if (!emailRecoveryIsCurrent(generation, visit, null)) return@launchEmailRecovery
                    _isUserLoggedIn.value = true
                    _currentUserId.value = it.id
                    _assistedRecoveryCaseId.value = null
                    context.getSharedPreferences("recovery_request", Context.MODE_PRIVATE)
                        .edit().remove("case_id").apply()
                    AuthState.Success(it)
                },
                onFailure = {
                    if (it is LocalFallbackPasswordChangedNeedsTwoFactorException) {
                        val generation = ++emailRecoveryGeneration
                        _emailRecovery.value = null
                        authEntryOperation { persistEmailRecovery(null, generation, visit); Result.success(Unit) }
                        if (!emailRecoveryIsCurrent(generation, visit, null)) return@launchEmailRecovery
                        AuthState.PasswordChangedNeedsTwoFactor
                    } else AuthState.Error(mapAuthError(it))
                }
            )
        }
    }

    fun startNewAssistedRecoveryRequest() {
        if (_assistedRecoverySubmitting.value) return
        _recoveryEvidence.value = emptyList()
        _assistedRecoveryCaseId.value = null
        _assistedRecoveryMessage.value = null
        context.getSharedPreferences("recovery_request", Context.MODE_PRIVATE)
            .edit().remove("case_id").apply()
    }

    fun confirmSavedPasswordRecoveryCode(
        email: String,
        recoveryCode: String,
        newPassword: String,
        secondFactorCode: String?
    ) {
        if (_authState.value is AuthState.Loading) return
        _authState.value = AuthState.Loading
        authEntryTasks.scope.launch {
            _authState.value = AuthState.Loading
            val result = authEntryOperation { authRepository.confirmSavedPasswordRecoveryCode(
                email.trim().lowercase(), recoveryCode, newPassword, secondFactorCode
            ) }
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

    fun completeMigratedPasswordReset(migrationToken: String, newPassword: String) {
        if (_authState.value is AuthState.Loading) return
        _authState.value = AuthState.Loading
        authEntryTasks.scope.launch {
            _authState.value = AuthState.Loading
            val result = authEntryOperation { authRepository.completeMigratedLocalPasswordReset(migrationToken, newPassword) }
            _authState.value = result.fold(
                onSuccess = {
                    _isUserLoggedIn.value = true
                    _currentUserId.value = it.id
                    AuthState.Success(it)
                },
                onFailure = {
                    if (it is LocalFallbackPasswordChangedNeedsTwoFactorException)
                        AuthState.PasswordChangedNeedsTwoFactor
                    else AuthState.Error(mapAuthError(it))
                }
            )
        }
    }

    /**
     * Finaliza la migración únicamente con el permiso firmado que el servidor
     * entrega después de comprobar la contraseña anterior de la cuenta.
     */
    fun setupMigratedPassword(email: String, migrationToken: String?, newPassword: String) {
        if (_authState.value is AuthState.Loading) return
        _authState.value = AuthState.Loading
        authEntryTasks.scope.launch {
            val normalizedEmail = email.trim().lowercase()
            val token = migrationToken?.trim().orEmpty()
            val nextPassword = newPassword

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
                val result = authEntryOperation { authRepository.completeMigratedLocalPasswordReset(token, nextPassword) }
                _authState.value = result.fold(
                    onSuccess = {
                        _isUserLoggedIn.value = true
                        _currentUserId.value = it.id
                        AuthState.Success(it)
                    },
                    onFailure = {
                        if (it is LocalFallbackPasswordChangedNeedsTwoFactorException)
                            AuthState.PasswordChangedNeedsTwoFactor
                        else AuthState.Error(mapAuthError(it))
                    }
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
        if (_authState.value is AuthState.Loading) return
        _authState.value = AuthState.Loading
        authEntryTasks.scope.launch {
            val normalizedEmail = email.trim().lowercase()
            val nextPassword = newPassword
            val nextConfirmPassword = confirmPassword

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
            val result = authEntryOperation { authRepository.directPasswordReset(normalizedEmail, nextPassword) }
            _authState.value = result.fold(
                onSuccess = {
                    _isUserLoggedIn.value = true
                    _currentUserId.value = it.id
                    AuthState.Success(it)
                },
                onFailure = {
                    if (it is LocalFallbackPasswordChangedNeedsTwoFactorException)
                        AuthState.PasswordChangedNeedsTwoFactor
                    else AuthState.Error(mapAuthError(it))
                }
            )
        }
    }

    fun updatePassword(newPassword: String) {
        if (_authState.value is AuthState.Loading) return
        _authState.value = AuthState.Loading
        authEntryTasks.scope.launch {
            _authState.value = AuthState.Loading
            val result = authEntryOperation { authRepository.updatePassword(newPassword) }
            _authState.value = result.fold(
                onSuccess = { AuthState.PasswordChanged },
                onFailure = { 
                    var message = mapAuthError(it)
                    AuthState.Error(message) 
                }
            )
        }
    }

    fun changePassword(currentPassword: String, newPassword: String) {
        if (_authState.value is AuthState.Loading) return
        _authState.value = AuthState.Loading
        authEntryTasks.scope.launch {
            val current = currentPassword
            val next = newPassword
            passwordChangeValidationError(current, next)?.let { validationError ->
                _authState.value = AuthState.Error(validationError)
                return@launch
            }

            _authState.value = AuthState.Loading
            _authState.value = authEntryOperation { authRepository.changePassword(current, next) }.fold(
                onSuccess = { AuthState.PasswordChanged },
                onFailure = { AuthState.Error(mapAuthError(it)) }
            )
        }
    }
}







