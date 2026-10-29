package conectate.com.data.repository.collection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal object CollectionPendingMessageRetrySupport {
    suspend fun retryPendingMessageSend(
        messageId: String,
        maxAttempts: Int = 4,
        sendAction: suspend () -> Result<Unit>,
        isDuplicateKeyError: (Exception) -> Boolean,
        isRetryableNetworkError: (Exception) -> Boolean,
        onConfirmedSuccess: suspend () -> Unit,
        onDuplicateTreatedAsSuccess: suspend () -> Unit,
        onTerminalFailure: suspend (Exception) -> Unit,
        failureMessage: String
    ): Result<Unit> {
        var lastError: Exception? = null

        for (attempt in 0 until maxAttempts) {
            try {
                currentCoroutineContext().ensureActive()
                val pushResult = sendAction()
                currentCoroutineContext().ensureActive()
                if (pushResult.isFailure) {
                    throw (pushResult.exceptionOrNull() ?: Exception("Unknown push error"))
                }
                onConfirmedSuccess()
                return Result.success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                lastError = e

                if (isDuplicateKeyError(e)) {
                    android.util.Log.w("CollectionPendingRetry", "Duplicate insert for $messageId detected treating as delivered")
                    onDuplicateTreatedAsSuccess()
                    return Result.success(Unit)
                }

                val isLastAttempt = attempt == maxAttempts - 1
                if (!isRetryableNetworkError(e) || isLastAttempt) {
                    onTerminalFailure(lastError)
                    return Result.failure(lastError)
                }

                val backoffMs = 1000L * (1L shl minOf(attempt, 3))
                delay(backoffMs)
            }
        }

        return Result.failure(lastError ?: Exception(failureMessage))
    }
}


