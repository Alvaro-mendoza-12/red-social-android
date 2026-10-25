package conectate.com.presentation.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A repository may throw during session hydration, not only return Result.failure. */
internal suspend fun <T> authEntryOperation(operation: suspend () -> Result<T>): Result<T> = try {
    val result = operation()
    currentCoroutineContext().ensureActive()
    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    result
} catch (cancelled: CancellationException) { throw cancelled }
catch (error: Exception) { Result.failure(error) }
