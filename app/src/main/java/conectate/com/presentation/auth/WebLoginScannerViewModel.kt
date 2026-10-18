package conectate.com.presentation.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.StoredAccountSession
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.WebLoginPairingRepository
import conectate.com.data.repository.WebLoginPairingSession
import conectate.com.presentation.common.toUserFacingMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WebLoginScannerUiState(
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val linkAfterScan: Boolean = false,
    val currentUserId: String? = null,
    val currentUserEmail: String? = null,
    val storedAccounts: List<StoredAccountSession> = emptyList(),
    val selectedAccountId: String? = null,
    val lastPayload: String? = null,
    val resolvedPairId: String? = null,
    val resolvedNonce: String? = null,
    val pairing: WebLoginPairingSession? = null,
    val infoMessage: String? = null,
    val errorMessage: String? = null
) {
    val hasCurrentSession: Boolean
        get() = !currentUserId.isNullOrBlank() || !currentUserEmail.isNullOrBlank()

    val hasAnyAvailableAccount: Boolean
        get() = hasCurrentSession || storedAccounts.isNotEmpty()
}

@HiltViewModel
class WebLoginScannerViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val webLoginPairingRepository: WebLoginPairingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        WebLoginScannerUiState(
            currentUserId = authRepository.getCurrentUserId(),
            currentUserEmail = authRepository.getCurrentUserEmail()
        )
    )
    val uiState: StateFlow<WebLoginScannerUiState> = _uiState.asStateFlow()

    init {
        observeStoredAccounts()
        observeSession()
    }

    private fun observeStoredAccounts() {
        viewModelScope.launch {
            authRepository.storedAccounts.collect { accounts ->
                _uiState.update { state ->
                    val selectedAccountId = when {
                        state.hasCurrentSession -> state.selectedAccountId
                        state.selectedAccountId != null -> state.selectedAccountId
                        accounts.size == 1 -> accounts.first().id
                        else -> null
                    }
                    state.copy(
                        storedAccounts = accounts,
                        selectedAccountId = selectedAccountId
                    )
                }
            }
        }
    }

    private fun observeSession() {
        viewModelScope.launch {
            authRepository.getAuthStateFlow().collect {
                val currentUserId = authRepository.getCurrentUserId()
                val currentUserEmail = authRepository.getCurrentUserEmail()
                _uiState.update { state ->
                    state.copy(
                        currentUserId = currentUserId,
                        currentUserEmail = currentUserEmail,
                        selectedAccountId = state.selectedAccountId.takeIf { selected ->
                            !selected.isNullOrBlank() && state.storedAccounts.any { it.id == selected }
                        }
                    )
                }
            }
        }
    }

    fun selectCurrentSession() {
        _uiState.update {
            it.copy(
                selectedAccountId = null,
                infoMessage = null,
                errorMessage = null
            )
        }
    }

    fun selectStoredAccount(accountId: String) {
        _uiState.update {
            it.copy(
                selectedAccountId = accountId,
                infoMessage = null,
                errorMessage = null
            )
        }
    }

    fun clearFeedback() {
        _uiState.update {
            it.copy(
                infoMessage = null,
                errorMessage = null
            )
        }
    }

    fun onCameraPermissionDenied() {
        _uiState.update {
            it.copy(
                errorMessage = "Sin permiso de cámara no puedo abrir el escáner. Puedes concederlo o pegar el QR manualmente."
            )
        }
    }

    fun submitPayload(rawPayload: String, linkAfterScan: Boolean = false) {
        if (uiState.value.isLoading || uiState.value.isSubmitting) return
        val parsed = WebLoginQrPayloadParser.parse(rawPayload)
        if (parsed == null) {
            _uiState.update {
                it.copy(
                    errorMessage = "Ese QR no pertenece a Novvin Web o está incompleto."
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    linkAfterScan = linkAfterScan,
                    lastPayload = rawPayload.trim(),
                    resolvedPairId = parsed.pairId,
                    resolvedNonce = parsed.nonce,
                    errorMessage = null,
                    infoMessage = null
                )
            }

            val result = webLoginPairingRepository.getPairingStatus(
                pairId = parsed.pairId,
                nonce = parsed.nonce
            )

            _uiState.update { state ->
                result.fold(
                    onSuccess = { pairing ->
                        state.copy(
                            isLoading = false,
                            pairing = pairing
                        )
                    },
                    onFailure = { error ->
                        state.copy(
                            isLoading = false,
                            pairing = null,
                            errorMessage = error.toUserFacingMessage(
                                "No pudimos validar el QR de Novvin Web."
                            )
                        )
                    }
                )
            }
            if (linkAfterScan && result.getOrNull()?.status == "pending") approvePairing()
        }
    }

    fun loadPairing(
        pairId: String,
        nonce: String
    ) {
        submitPayload("conectate://web-login?pairId=$pairId&nonce=$nonce")
    }

    fun refreshPairing() {
        val payload = uiState.value.lastPayload ?: return
        submitPayload(payload)
    }

    fun approvePairing() {
        if (uiState.value.isSubmitting) return
        viewModelScope.launch {
            val pairing = uiState.value.pairing
            if (pairing == null) {
                _uiState.update { it.copy(errorMessage = "Primero escanea un QR válido.") }
                return@launch
            }

            if (pairing.status != "pending") {
                _uiState.update {
                    it.copy(
                        infoMessage = "Este acceso ya no está pendiente. Actualiza el estado para ver el resultado real."
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isSubmitting = true,
                    errorMessage = null,
                    infoMessage = null
                )
            }

            val sessionReady = ensureApprovalSession()
            if (sessionReady.isFailure) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = sessionReady.exceptionOrNull().toUserFacingMessage(
                            "No pudimos preparar la cuenta que aprobará este acceso."
                        )
                    )
                }
                return@launch
            }

            val accessToken = authRepository.getCurrentAccessTokenAwait()?.trim().orEmpty()
            val refreshToken = authRepository.getCurrentRefreshToken()?.trim().orEmpty()
            if (!webPairingApprovalSessionValid(accessToken, refreshToken, webLoginPairingRepository.requiresRefreshTokenForApproval)) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = "La cuenta elegida no tiene una sesión válida para aprobar el acceso web."
                    )
                }
                return@launch
            }

            val result = webLoginPairingRepository.approvePairing(
                pairId = uiState.value.resolvedPairId ?: pairing.pairId,
                nonce = uiState.value.resolvedNonce.orEmpty(),
                accessToken = accessToken,
                refreshToken = refreshToken
            )

            _uiState.update { state ->
                result.fold(
                    onSuccess = { updated ->
                        state.copy(
                            isSubmitting = false,
                            pairing = updated,
                            infoMessage = "Dispositivo vinculado. Ya puedes usar Novvin Web."
                        )
                    },
                    onFailure = { error ->
                        state.copy(
                            isSubmitting = false,
                            errorMessage = error.toUserFacingMessage(
                                "No pudimos aprobar este acceso web."
                            )
                        )
                    }
                )
            }
        }
    }

    fun rejectPairing() {
        viewModelScope.launch {
            val pairing = uiState.value.pairing
            if (pairing == null) {
                _uiState.update { it.copy(errorMessage = "Primero escanea un QR válido.") }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isSubmitting = true,
                    errorMessage = null,
                    infoMessage = null
                )
            }

            val sessionReady = ensureApprovalSession()
            if (sessionReady.isFailure) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = sessionReady.exceptionOrNull().toUserFacingMessage(
                            "No pudimos preparar la cuenta que rechazará este acceso."
                        )
                    )
                }
                return@launch
            }

            val accessToken = authRepository.getCurrentAccessTokenAwait()?.trim().orEmpty()
            if (accessToken.isBlank()) {
                _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        errorMessage = "La cuenta elegida no tiene una sesión válida para rechazar el acceso."
                    )
                }
                return@launch
            }

            val result = webLoginPairingRepository.rejectPairing(
                pairId = uiState.value.resolvedPairId ?: pairing.pairId,
                nonce = uiState.value.resolvedNonce.orEmpty(),
                accessToken = accessToken
            )

            _uiState.update { state ->
                result.fold(
                    onSuccess = { updated ->
                        state.copy(
                            isSubmitting = false,
                            pairing = updated,
                            infoMessage = "Acceso web rechazado."
                        )
                    },
                    onFailure = { error ->
                        state.copy(
                            isSubmitting = false,
                            errorMessage = error.toUserFacingMessage(
                                "No pudimos rechazar este acceso web."
                            )
                        )
                    }
                )
            }
        }
    }

    private suspend fun ensureApprovalSession(): Result<Unit> {
        val state = uiState.value
        val selectedAccount = state.selectedAccountId
            ?.let { selectedId -> state.storedAccounts.firstOrNull { it.id == selectedId } }

        if (selectedAccount == null) {
            val accessToken = authRepository.getCurrentAccessTokenAwait()?.trim().orEmpty()
            return if (accessToken.isNotBlank()) {
                Result.success(Unit)
            } else {
                Result.failure(
                    IllegalStateException(
                        "Necesitas una cuenta activa o guardada para aprobar el acceso web."
                    )
                )
            }
        }

        val currentUserId = authRepository.getCurrentUserId()
        val currentUserEmail = authRepository.getCurrentUserEmail()
        val sameAccount =
            (!selectedAccount.user_id.isNullOrBlank() && selectedAccount.user_id == currentUserId) ||
                selectedAccount.email.equals(currentUserEmail, ignoreCase = true)

        if (!sameAccount) {
            return authRepository.switchAccount(selectedAccount.id)
                .fold(
                    onSuccess = { Result.success(Unit) },
                    onFailure = { Result.failure(it) }
                )
        }

        return Result.success(Unit)
    }
}
