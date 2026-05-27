package conectate.com.data.repository.collection

import kotlinx.coroutines.delay

internal object CollectionDeleteRetrySupport {
    suspend fun retryRemoteDelete(
        logTag: String,
        messageId: String,
        maxAttempts: Int = 4,
        remoteDelete: suspend () -> Unit,
        isRetryableNetworkError: (Exception) -> Boolean
    ): Result<Unit> {
        var lastError: Exception? = null

        for (attempt in 0 until maxAttempts.coerceAtLeast(1)) {
            try {
                remoteDelete()
                return Result.success(Unit)
            } catch (e: Exception) {
                lastError = e
                val isLastAttempt = attempt == maxAttempts - 1
                if (!isRetryableNetworkError(e) || isLastAttempt) {
                    android.util.Log.w(
                        logTag,
                        "Delete sync failed for $messageId after ${attempt + 1} attempt(s)",
                        e
                    )
                    return Result.failure(e)
                }

                val backoffMs = 1_000L * (1L shl minOf(attempt, 3))
                android.util.Log.w(
                    logTag,
                    "Retrying delete sync for $messageId in ${backoffMs}ms (attempt ${attempt + 1}/$maxAttempts)",
                    e
                )
                delay(backoffMs)
            }
        }

        return Result.failure(lastError ?: Exception("Delete sync failed"))
    }
}


