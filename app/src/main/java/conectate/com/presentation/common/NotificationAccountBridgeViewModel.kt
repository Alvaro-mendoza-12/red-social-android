package conectate.com.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.MultiAccountInboxRepository
import conectate.com.util.storedAccountDisplayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject


@HiltViewModel
class NotificationAccountBridgeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val multiAccountInboxRepository: MultiAccountInboxRepository,
) : ViewModel() {
    private val opening = NotificationAccountOpening(viewModelScope,
        switchAccount = authRepository::switchAccountForNotification,
        acknowledge = multiAccountInboxRepository::acknowledgeAccount,
        failureMessage = { error ->
            when {
                looksLikeStoredAccountSessionIssue(error.message) ->
                    "La sesión de esta cuenta guardada venció. Inicia sesión con ella para abrir el contenido."
                error is java.util.concurrent.TimeoutException ->
                    "El cambio de cuenta está tardando demasiado. Inténtalo de nuevo."
                else -> "No pudimos abrir esta cuenta. Comprueba tu conexión e inténtalo de nuevo."
            }
        },
    )
    val isSwitching = opening.isSwitching
    val errorMessage = opening.errorMessage
    val completedRoute = opening.completedRoute
    val activeAccountLabel = combine(authRepository.getAuthStateFlow(), authRepository.storedAccounts) { session, accounts ->
        val user = session?.user
        accounts.firstOrNull { it.user_id?.equals(user?.id, ignoreCase = true) == true }
            ?.let(::storedAccountDisplayLabel)
            ?: user?.email?.substringBefore('@')
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun continueWithAccount(accountId: String, destinationRoute: String) {
        opening.start(accountId, destinationRoute)
    }

    fun clearErrorMessage() {
        opening.clearError()
    }

    fun resumeAfterSignIn(
        signedInUserId: String,
        destinationRoute: String,
        targetAccountId: String?,
        targetUserId: String?,
        targetEmail: String?,
    ) {
        val lease = authRepository.captureAccountLease()
        val stored = authRepository.storedAccounts.value.firstOrNull { it.id == targetAccountId }
        val user = authRepository.getAuthStateFlow().value?.user
        if (!lease.isCurrent() || !matchesNotificationSignIn(
                signedInUserId, lease.accountId, user?.email,
                targetUserId ?: stored?.user_id, targetEmail ?: stored?.email,
                authRepository.currentAccountUserIds(),
            )) {
            opening.wrongAccount()
            return
        }
        opening.complete(lease, destinationRoute, multiAccountInboxRepository::acknowledgeCurrentAccount)
    }

    fun takeCompletedRoute(): String? = opening.takeCompletedRoute()
    fun navigationFailed() = opening.navigationFailed()
}






