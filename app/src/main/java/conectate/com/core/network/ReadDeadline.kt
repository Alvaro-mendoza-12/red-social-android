package conectate.com.core.network

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deadlines for user-visible reads. They are deliberately shorter than upload
 * and background-sync timeouts: a feed should fall back to disk or an error
 * state instead of owning the screen indefinitely.
 */
object InteractiveReadDeadlines {
    const val FirstPageMillis = 10_000L
    const val InboxMillis = 10_000L
    const val ConversationHistoryMillis = 12_000L
    const val ProfileMillis = 10_000L
}

class ReadDeadlineExceededException(
    val timeoutMillis: Long,
) : IOException("La lectura superó el límite de ${timeoutMillis} ms.")

/**
 * Applies one total deadline to a read, including endpoint discovery/retry.
 * External cancellation is preserved so obsolete screen requests stop cleanly.
 */
suspend fun <T> runReadWithDeadline(
    timeoutMillis: Long,
    block: suspend () -> T,
): Result<T> {
    require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
    return try {
        // Result wraps a legitimate null value; only this deadline returns null.
        // withTimeoutOrNull also preserves a shorter timeout imposed by the caller.
        withTimeoutOrNull(timeoutMillis) { Result.success(block()) }
            ?: Result.failure(ReadDeadlineExceededException(timeoutMillis))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}
