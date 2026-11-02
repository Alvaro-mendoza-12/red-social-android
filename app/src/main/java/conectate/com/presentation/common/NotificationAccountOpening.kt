package conectate.com.presentation.common

import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

/** Owns one intentional account switch and the route waiting for its confirmed session. */
internal class NotificationAccountOpening(
    private val scope: CoroutineScope,
    private val switchAccount: suspend (String) -> Result<AuthSessionLease>,
    private val acknowledge: (String) -> Unit,
    private val failureMessage: (Throwable) -> String,
    private val timeoutMs: Long = 45_000L,
) {
    private val busy = MutableStateFlow(false)
    val isSwitching = busy.asStateFlow()
    private val error = MutableStateFlow<String?>(null)
    val errorMessage = error.asStateFlow()
    private val route = MutableStateFlow<String?>(null)
    val completedRoute = route.asStateFlow()
    private var completedOwner: AuthSessionLease? = null

    fun start(accountId: String, destination: String) {
        val account = accountId.trim()
        val target = destination.trim()
        if (busy.value || route.value != null || account.isEmpty() || target.isEmpty()) return
        // Reserve before launch: two clicks in the same frame are still one operation.
        busy.value = true
        error.value = null
        route.value = null
        completedOwner = null
        scope.launch {
            try {
                val result = withTimeoutOrNull(timeoutMs) { switchAccount(account) }
                    ?: Result.failure(TimeoutException("Account switch timed out"))
                currentCoroutineContext().ensureActive()
                val owner = result.getOrThrow()
                owner.requireCurrent()
                acknowledge(account)
                owner.requireCurrent()
                complete(owner, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error.value = failureMessage(failure)
            }
        }.invokeOnCompletion { busy.value = false }
    }

    fun complete(owner: AuthSessionLease, destination: String, acknowledge: () -> Unit = {}) {
        val target = destination.trim().takeIf(String::isNotEmpty) ?: return
        try {
            owner.requireCurrent()
            acknowledge()
            owner.requireCurrent()
            error.value = null
            completedOwner = owner
            route.value = target
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error.value = failureMessage(failure) }
    }

    /** Consumed before navigation, with the same generation that completed the switch. */
    fun takeCompletedRoute(): String? {
        val destination = route.value
        val owner = completedOwner
        route.value = null
        completedOwner = null
        return destination?.takeIf { owner?.isCurrent() == true }
    }

    fun clearError() { error.value = null }
    fun wrongAccount() { error.value = "Esta notificación pertenece a otra cuenta. Inicia sesión con la cuenta que la recibió." }
    fun navigationFailed() { error.value = "La cuenta se abrió, pero no pudimos mostrar este contenido. Puedes volver a intentarlo." }
}

/** A canonical recipient id wins over an old or conflicting email address. */
internal fun matchesNotificationSignIn(
    signedInUserId: String,
    currentUserId: String?,
    currentEmail: String?,
    targetUserId: String?,
    targetEmail: String?,
    currentUserIds: Set<String> = emptySet(),
): Boolean {
    val current = currentUserId?.trim()?.takeIf(String::isNotEmpty) ?: return false
    if (!current.equals(signedInUserId.trim(), ignoreCase = true)) return false
    val expectedId = targetUserId?.trim()?.takeIf(String::isNotEmpty)
    if (expectedId != null) return expectedId.equals(current, ignoreCase = true) ||
        currentUserIds.any { expectedId.equals(it, ignoreCase = true) }
    val expectedEmail = targetEmail?.trim()?.takeIf(String::isNotEmpty) ?: return false
    return expectedEmail.equals(currentEmail?.trim(), ignoreCase = true)
}
