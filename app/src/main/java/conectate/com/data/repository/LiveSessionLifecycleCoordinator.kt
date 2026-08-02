package conectate.com.data.repository

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Serializes native live-session teardown and lets every caller await the same result.
 * Socket events, navigation and foreground-service actions can all request closure,
 * but only the first request is allowed to release WebRTC resources.
 */
internal class LiveSessionCloseCoordinator {
    private val closeResult = AtomicReference<CompletableDeferred<Result<Unit>>?>(null)

    suspend fun closeOnce(block: suspend () -> Result<Unit>): Result<Unit> {
        while (true) {
            closeResult.get()?.let { active -> return active.await() }

            val candidate = CompletableDeferred<Result<Unit>>()
            if (closeResult.compareAndSet(null, candidate)) {
                val result = withContext(NonCancellable) {
                    runCatching { block() }
                        .getOrElse { error -> Result.failure(error) }
                }
                candidate.complete(result)
                return candidate.await()
            }
        }
    }

    fun resetForNewSession(): Boolean {
        while (true) {
            val current = closeResult.get() ?: return true
            if (!current.isCompleted) return false
            if (closeResult.compareAndSet(current, null)) return true
        }
    }
}

/** Prevents overlapping camera/screen transitions while native callbacks are pending. */
internal class LiveMediaTransitionGate {
    private val transitioning = AtomicBoolean(false)

    fun tryStart(): Boolean = transitioning.compareAndSet(false, true)

    fun finish() {
        transitioning.set(false)
    }

    fun isTransitioning(): Boolean = transitioning.get()
}

internal enum class PendingProjectionCommit {
    ACTIVATED,
    STOPPED,
    INVALIDATED
}

internal enum class ProjectionStopDisposition {
    PENDING,
    ACTIVE,
    IGNORED
}

/**
 * Owns the MediaProjection token state as one atomic state machine.
 *
 * MediaProjection.Callback can run while the WebRTC executor is returning from
 * startCapture. Keeping pending -> active promotion and callback classification
 * under the same monitor prevents a stop callback from falling between those
 * two operations and being lost.
 */
internal class LiveProjectionStateCoordinator {
    private val lock = Any()
    private var activeToken: String? = null
    private var pendingToken: String? = null
    private var pendingStopped = false
    private var suppressedToken: String? = null

    fun activeToken(): String? = synchronized(lock) { activeToken }

    fun beginPending(token: String, suppressToken: String?) = synchronized(lock) {
        pendingToken = token
        pendingStopped = false
        suppressedToken = suppressToken
    }

    fun commitPending(token: String): PendingProjectionCommit = synchronized(lock) {
        if (pendingToken != token) return@synchronized PendingProjectionCommit.INVALIDATED

        pendingToken = null
        return@synchronized if (pendingStopped) {
            pendingStopped = false
            activeToken = null
            PendingProjectionCommit.STOPPED
        } else {
            activeToken = token
            PendingProjectionCommit.ACTIVATED
        }
    }

    fun markStopped(token: String): ProjectionStopDisposition = synchronized(lock) {
        when (token) {
            pendingToken -> {
                pendingStopped = true
                ProjectionStopDisposition.PENDING
            }
            activeToken -> ProjectionStopDisposition.ACTIVE
            else -> ProjectionStopDisposition.IGNORED
        }
    }

    fun suppress(token: String?) = synchronized(lock) {
        suppressedToken = token
    }

    fun clearSuppressed(token: String?) = synchronized(lock) {
        if (suppressedToken == token) suppressedToken = null
    }

    fun isSuppressed(token: String): Boolean = synchronized(lock) {
        suppressedToken == token
    }

    fun isActive(token: String): Boolean = synchronized(lock) {
        activeToken == token
    }

    fun clearActive(token: String? = null) = synchronized(lock) {
        if (token == null || activeToken == token) activeToken = null
    }

    fun clearPending(token: String) = synchronized(lock) {
        if (pendingToken == token) {
            pendingToken = null
            pendingStopped = false
        }
    }

    fun reset() = synchronized(lock) {
        activeToken = null
        pendingToken = null
        pendingStopped = false
        suppressedToken = null
    }
}
