package conectate.com.data.repository.collection

import kotlinx.coroutines.delay

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
                val pushResult = sendAction()
                if (pushResult.isFailure) {
                    throw (pushResult.exceptionOrNull() ?: Exception("Unknown push error"))
                }
                onConfirmedSuccess()
                return Result.success(Unit)
            } catch (e: Exception) {
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


