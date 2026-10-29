package conectate.com.data.repository.collection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A rejected delete can restore only its own unchanged optimistic version, in the same session. */
internal suspend fun <T : Any> deleteCollectionMessage(
    isCurrentSession: () -> Boolean,
    load: suspend () -> T?,
    markDeleted: (T) -> T,
    save: suspend (T) -> Unit,
    onOptimistic: suspend (T) -> Unit = {},
    remoteDelete: suspend (T) -> Result<Unit>,
): Result<Unit> {
    suspend fun requireCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isCurrentSession()) throw CancellationException("La cuenta cambió durante el borrado.")
    }

    var previous: T? = null
    var optimistic: T? = null
    val failure = try {
        requireCurrent()
        val original = load() ?: return Result.failure(IllegalStateException("Mensaje no encontrado"))
        requireCurrent()
        previous = original
        val deleted = markDeleted(original)
        optimistic = deleted
        save(deleted)
        requireCurrent()
        onOptimistic(deleted)
        requireCurrent()
        remoteDelete(deleted).getOrThrow()
        requireCurrent()
        return Result.success(Unit)
    } catch (cancelled: CancellationException) {
        // The server may already have accepted the command. A subsequent sync
        // reconciles that ambiguous outcome; another account must never receive a rollback.
        throw cancelled
    } catch (error: Exception) {
        error
    }

    val original = previous
    val expected = optimistic
    if (original != null && expected != null) {
        try {
            requireCurrent()
            val latest = load()
            requireCurrent()
            if (latest == expected) save(original)
            requireCurrent()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (restoreError: Exception) {
            if (restoreError !== failure) failure.addSuppressed(restoreError)
        }
    }
    return Result.failure(failure)
}
