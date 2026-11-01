package conectate.com.data.repository.message

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import conectate.com.data.repository.SessionWriteGuard
import conectate.com.data.supabase.AuthSessionLease

/** Delivery runs off the UI thread; the durable-queue callback keeps the caller's context. */
internal suspend fun <T> executeMessageSend(
    onQueued: suspend () -> Unit,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    delivery: suspend (queued: suspend () -> Unit) -> T,
): T {
    val callerContext = currentCoroutineContext().minusKey(Job)
    return withContext(ioDispatcher) {
        delivery { withContext(callerContext) { onQueued() } }
    }
}

/** Capture the sender and session generation before a dispatcher or message-lock suspension. */
internal suspend fun <T> executeOwnedMessageSend(
    accountLease: AuthSessionLease,
    writeGuard: SessionWriteGuard,
    onQueued: suspend () -> Unit = {},
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    delivery: suspend (SessionWriteGuard.Snapshot, queued: suspend () -> Unit) -> Result<T>,
): Result<T> {
    if (accountLease.accountId == null) return Result.failure(IllegalStateException("Inicia sesión para enviar mensajes."))
    val session = writeGuard.capture(accountLease, currentCoroutineContext()[Job])
    writeGuard.requireValid(session)
    return executeMessageSend(onQueued = {
        writeGuard.requireValid(session)
        onQueued()
    }, ioDispatcher = ioDispatcher) { queued ->
        writeGuard.requireValid(session)
        val result = delivery(session, queued)
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        writeGuard.requireValid(session)
        result
    }
}
