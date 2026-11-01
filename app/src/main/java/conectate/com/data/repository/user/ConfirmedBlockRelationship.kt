package conectate.com.data.repository.user

import conectate.com.data.repository.BlockRelationship
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** A failed lookup is not evidence that a user unblocked anyone. */
internal fun confirmedBlockRelationships(
    currentAccount: () -> String?,
    intervalMillis: Long = 5_000,
    read: suspend () -> Result<BlockRelationship>,
): Flow<BlockRelationship> = flow {
    fun account() = currentAccount()?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    val owner = account() ?: return@flow
    while (account() == owner) {
        val result = try { read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
        currentCoroutineContext().ensureActive()
        if (account() != owner) break
        (result.exceptionOrNull() as? CancellationException)?.let { throw it }
        result.getOrNull()?.let { emit(it) }
        delay(intervalMillis)
    }
}.distinctUntilChanged()
