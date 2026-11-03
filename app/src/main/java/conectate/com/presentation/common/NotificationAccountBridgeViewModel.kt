package conectate.com.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.repository.AuthRepository
import conectate.com.data.repository.MultiAccountInboxRepository
import conectate.com.util.storedAccountDisplayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

internal data class RestoredNotificationSignInSession(val userId: String, val credentialStamp: String)

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
        val lease = matchingSignInLease(signedInUserId, targetAccountId, targetUserId, targetEmail)
        if (lease == null) {
            opening.wrongAccount()
            return
        }
        opening.complete(lease, destinationRoute) { multiAccountInboxRepository.acknowledgeCurrentAccount(lease) }
    }

    internal fun observeRestoredSignInSession(
        targetAccountId: String?, targetUserId: String?, targetEmail: String?,
    ): Flow<RestoredNotificationSignInSession?> = combine(authRepository.getAuthStateFlow(), authRepository.isInitializing,
        authRepository.accountSwitchUiState, authRepository.storedAccounts) { _, _, _, _ ->
        currentRestoredSignInSession(targetAccountId, targetUserId, targetEmail)
    }

    internal fun currentRestoredSignInSession(
        targetAccountId: String?, targetUserId: String?, targetEmail: String?,
    ): RestoredNotificationSignInSession? {
        if (authRepository.isInitializing.value || authRepository.accountSwitchUiState.value.isSwitching) return null
        val lease = matchingSignInLease(null, targetAccountId, targetUserId, targetEmail) ?: return null
        val stamp = signInSessionStamp() ?: return null
        return RestoredNotificationSignInSession(lease.accountId ?: return null, stamp).takeIf { lease.isCurrent() }
    }

    /** Only a fingerprint enters saved navigation; a bearer credential must never enter it. */
    internal fun signInSessionStamp(): String? {
        val lease = authRepository.captureAccountLease()
        val session = authRepository.getAuthStateFlow().value ?: return if (lease.accountId == null) "signed-out" else null
        if (!lease.isCurrent() || !session.user.id.equals(lease.accountId, true)) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest((session.user.id.lowercase(java.util.Locale.ROOT) + '\u0000' + session.accessToken).toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(64) { digest.forEach { byte ->
            val value = byte.toInt() and 255
            append(hex[value ushr 4]); append(hex[value and 15])
        } }.takeIf { lease.isCurrent() }
    }

    private fun matchingSignInLease(
        signedInUserId: String?, targetAccountId: String?, targetUserId: String?, targetEmail: String?,
    ): conectate.com.data.supabase.AuthSessionLease? {
        val lease = authRepository.captureAccountLease()
        val stored = authRepository.storedAccounts.value.firstOrNull { it.id == targetAccountId }
        val user = authRepository.getAuthStateFlow().value?.user ?: return null
        if (!lease.isCurrent() || !matchesNotificationSignIn(
                signedInUserId ?: user.id, lease.accountId, user.email,
                targetUserId ?: stored?.user_id, targetEmail ?: stored?.email,
                authRepository.currentAccountUserIds(),
            )) {
            return null
        }
        return lease
    }

    fun takeCompletedRoute(): String? = opening.takeCompletedRoute()
    fun navigationFailed() = opening.navigationFailed()
}






