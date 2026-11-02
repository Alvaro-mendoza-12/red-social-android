package conectate.com.data.repository

import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Credentials and results belong to the same account generation, even across token renewal. */
internal suspend fun <T> notificationAccountCall(
    lease: AuthSessionLease,
    tokenForAccount: suspend (String) -> String?,
    action: suspend (owner: String, accessToken: String) -> Result<T>,
): Result<T> {
    val owner = lease.accountId
        ?: return Result.failure(IllegalStateException("Inicia sesión para continuar."))
    return try {
        currentCoroutineContext().ensureActive()
        lease.requireCurrent()
        val token = tokenForAccount(owner)
        currentCoroutineContext().ensureActive()
        lease.requireCurrent()
        if (token.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Tu sesión venció. Inicia sesión de nuevo."))
        }
        val result = action(owner, token)
        currentCoroutineContext().ensureActive()
        lease.requireCurrent()
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        lease.requireCurrent()
        Result.failure(error)
    }
}
