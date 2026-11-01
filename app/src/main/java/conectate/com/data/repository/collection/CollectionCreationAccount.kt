package conectate.com.data.repository.collection

import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Own the creation before any icon processing or repository dispatcher switch. */
internal suspend fun <T> withCollectionCreationAccount(
    lease: AuthSessionLease,
    expectedAccountId: String?,
    tokenForAccount: suspend (String) -> String?,
    operation: suspend (accountId: String, accessToken: String, requireCurrent: () -> Unit) -> Result<T>,
): Result<T> = try {
    val owner = lease.accountId
    if (owner == null) Result.failure(IllegalStateException("Inicia sesión para continuar."))
    else {
        if (expectedAccountId != null && !owner.equals(expectedAccountId.trim(), ignoreCase = true))
            throw CancellationException("La cuenta cambió antes de crear este espacio.")
        lease.requireCurrent()
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val requireCurrent = { context.ensureActive(); lease.requireCurrent() }
            requireCurrent()
            val token = tokenForAccount(owner)
            requireCurrent()
            if (token.isNullOrBlank()) Result.failure(IllegalStateException("Vuelve a iniciar sesión para continuar."))
            else operation(owner, token, requireCurrent).also { requireCurrent() }
        }
    }
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) {
    currentCoroutineContext().ensureActive()
    if (lease.accountId != null) lease.requireCurrent()
    Result.failure(error)
}
