package conectate.com.presentation.auth

import conectate.com.data.model.StoredAccountSession
import kotlinx.coroutines.CancellationException

internal fun webPairingApprovalSessionValid(accessToken: String, refreshToken: String, requiresRefreshToken: Boolean): Boolean =
    accessToken.isNotBlank() && (!requiresRefreshToken || refreshToken.isNotBlank())

/** Resolves approval credentials without importing another account into the app. */
internal suspend fun resolveWebPairingApprovalSession(
    selectedAccountId: String?,
    storedAccounts: List<StoredAccountSession>,
    currentIdentity: () -> Pair<String?, String?>,
    currentCredentials: suspend () -> Pair<String, String>,
): Result<Pair<String, String>> {
    val selected = selectedAccountId?.let { id -> storedAccounts.firstOrNull { it.id == id } }
    if (selectedAccountId != null && selected == null) {
        return Result.failure(IllegalStateException("La cuenta elegida ya no está guardada. Vuelve a seleccionarla."))
    }
    val identity = currentIdentity()
    val sameAccount = selected == null ||
        (!selected.user_id.isNullOrBlank() && selected.user_id == identity.first) ||
        selected.email.equals(identity.second, ignoreCase = true)
    if (!sameAccount) {
        return selected!!.access_token.trim().takeIf { it.isNotBlank() }
            ?.let { Result.success(it to selected.refresh_token.trim()) }
            ?: Result.failure(IllegalStateException("Vuelve a iniciar sesión en la cuenta elegida para vincularla."))
    }
    return try {
        val credentials = currentCredentials()
        if (credentials.first.isBlank() || currentIdentity() != identity) {
            Result.failure(IllegalStateException("La sesión cambió. Selecciona la cuenta y vuelve a intentarlo."))
        } else Result.success(credentials)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
