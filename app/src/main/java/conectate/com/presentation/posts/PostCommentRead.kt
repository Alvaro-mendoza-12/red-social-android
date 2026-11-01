package conectate.com.presentation.posts

import conectate.com.data.model.Comment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Read errors and timeouts are distinct from a successful empty conversation. */
internal suspend fun readPostComments(
    timeoutMs: Long = 30_000,
    read: suspend () -> Flow<List<Comment>>,
): Result<List<Comment>> = try {
    val result = withTimeoutOrNull(timeoutMs) { read().first() }
        ?: throw java.io.IOException("Comment read timed out")
    currentCoroutineContext().ensureActive()
    Result.success(result)
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(error) }
