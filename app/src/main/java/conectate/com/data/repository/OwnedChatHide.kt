package conectate.com.data.repository

import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ChatVisibilityOperation { HIDE, CLEAR }

/** Remote work never holds the local account gate. The caller owns the atomic storage commit. */
internal suspend fun executeOwnedChatVisibility(
    owner: AuthSessionLease,
    token: suspend () -> String,
    hide: (suspend (String) -> Result<Unit>)?,
    clear: (suspend (String) -> Result<Unit>)?,
    commit: suspend () -> Unit,
    operation: ChatVisibilityOperation = ChatVisibilityOperation.HIDE,
): Result<Unit> {
    suspend fun requireCurrent() {
        currentCoroutineContext().ensureActive()
        owner.requireCurrent()
    }
    return try {
        requireCurrent()
        val accessToken = token()
        requireCurrent()
        val remote = if (operation == ChatVisibilityOperation.CLEAR) requireNotNull(clear) else requireNotNull(hide)
        val hidden = remote(accessToken)
        requireCurrent()
        (hidden.exceptionOrNull() as? CancellationException)?.let { throw it }
        if (operation == ChatVisibilityOperation.HIDE && hidden.isFailure && clear != null) {
            clear(accessToken).getOrThrow()
            requireCurrent()
        } else hidden.getOrThrow()
        requireCurrent()
        commit()
        requireCurrent()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        requireCurrent()
        Result.failure(error)
    }
}

/** A cancellation undoes only local optimism; the caller must fence rollback with its lease. */
internal suspend fun runChatHideWithRollback(
    action: suspend () -> Result<Unit>,
    rollback: (Throwable?) -> Unit,
) {
    try {
        val error = action().exceptionOrNull()
        if (error is CancellationException) throw error
        error?.let(rollback)
    } catch (cancelled: CancellationException) {
        rollback(null)
        throw cancelled
    }
}
