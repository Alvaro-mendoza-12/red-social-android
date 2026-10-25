package conectate.com.data.repository

import conectate.com.data.model.LiveCollaborationCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** An unavailable capability read is not proof that the host uses legacy P2P. */
internal fun liveViewerUsesSfu(capabilities: Result<LiveCollaborationCapabilities>): Boolean =
    capabilities.getOrThrow().sfuAvailable

/** Bound a suspended admission/media handshake without swallowing navigation/logout cancellation. */
internal suspend fun <T> awaitLiveConnection(
    timeoutMs: Long = 35_000L,
    connect: suspend () -> Result<T>,
): Result<T> = withTimeoutOrNull(timeoutMs) {
    val result = connect()
    currentCoroutineContext().ensureActive()
    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    result
} ?: Result.failure(IllegalStateException(
    "La conexión del directo tardó demasiado. Revisa tu conexión y vuelve a intentarlo.",
))
