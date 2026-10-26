package conectate.com.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** A transport connection is not proof that the server admitted this LIVE room. */
internal class LiveSocketStartup(private val liveId: String) {
    private val accepted = CompletableDeferred<Unit>()
    val isPending: Boolean get() = !accepted.isCompleted

    fun acceptRoom(acceptedLiveId: String): Boolean =
        liveId.isNotBlank() && liveId.equals(acceptedLiveId.trim(), ignoreCase = true) && accepted.complete(Unit)

    fun reject(error: Throwable): Boolean = accepted.completeExceptionally(error)

    fun cancel() { accepted.cancel(CancellationException("The LIVE session closed during signaling admission")) }

    suspend fun await() { accepted.await() }
}
