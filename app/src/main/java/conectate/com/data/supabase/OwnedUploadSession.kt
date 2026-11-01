package conectate.com.data.supabase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/** A frozen owner for upload credentials, resume keys and UI callbacks. */
internal class OwnedUploadSession(
    private val lease: AuthSessionLease,
    expectedAccountId: String?,
    private val tokenForAccount: suspend (String) -> String?,
) {
    val accountId = (expectedAccountId ?: lease.accountId)?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        ?: error("Inicia sesión para subir un archivo.")

    init {
        if (accountId != lease.accountId) throw CancellationException("La cuenta cambió antes de subir el archivo.")
        lease.requireCurrent()
    }

    suspend fun accessToken(): String {
        currentCoroutineContext().ensureActive()
        requireCurrent()
        val token = tokenForAccount(accountId)
        currentCoroutineContext().ensureActive()
        requireCurrent()
        return token?.trim()?.takeIf(String::isNotBlank)
            ?: error("No pudimos renovar tu sesión para subir el archivo. Reintenta.")
    }

    fun requireCurrent() = lease.requireCurrent()

    /** OkHttp writer threads must receive IOException, never an uncaught cancellation. */
    fun requireTransportCurrent() {
        try { requireCurrent() }
        catch (cancelled: CancellationException) { throw IOException("La sesión cambió durante la subida.", cancelled) }
    }

    fun progress(callback: (Float) -> Unit): (Float) -> Unit = { value ->
        try { requireCurrent(); callback(value) }
        catch (cancelled: CancellationException) { throw IOException("La sesión cambió durante la subida.", cancelled) }
    }

    /** A legacy Result-wrapped cancellation must never authorize a second transport. */
    suspend fun <T> uploadWithFallback(
        primary: suspend () -> Result<T>,
        fallback: (suspend () -> Result<T>)? = null,
    ): Result<T> {
        suspend fun attempt(operation: suspend () -> Result<T>): Result<T> {
            currentCoroutineContext().ensureActive()
            requireCurrent()
            val result = try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
            currentCoroutineContext().ensureActive()
            requireCurrent()
            (result.exceptionOrNull() as? CancellationException)?.let { throw it }
            return result
        }
        val result = attempt(primary)
        return if (result.isSuccess || fallback == null) result else attempt(fallback)
    }
}
