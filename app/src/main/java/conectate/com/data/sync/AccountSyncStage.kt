package conectate.com.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

/** A cycle may not continue with a different account after an awaited stage. */
internal class AccountSyncStage(
    expectedAccountId: String,
    private val currentAccount: () -> String?,
) {
    private val owner = expectedAccountId.trim().lowercase(Locale.US)

    suspend fun <T> run(operation: suspend () -> Result<T>): Result<T> {
        requireActiveAccount()
        val result = operation()
        requireActiveAccount()
        // Some repositories return cancellation inside Result rather than throw.
        // It must stop the cycle before another repository or metric is invoked.
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result
    }

    private suspend fun requireActiveAccount() {
        currentCoroutineContext().ensureActive()
        if (owner.isBlank() || currentAccount()?.trim()?.lowercase(Locale.US) != owner) {
            throw CancellationException("Sync account changed")
        }
    }
}
