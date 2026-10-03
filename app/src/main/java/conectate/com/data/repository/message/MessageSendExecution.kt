package conectate.com.data.repository.message

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

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
