package conectate.com.data.localfallback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Coalesces failed discovery as well as successful checks; waiting requests never queue full retries. */
internal class EndpointRefreshGate(
    private val nowMs: () -> Long,
    private val failureCooldownMs: Long = 5_000L,
) {
    private val mutex = Mutex()
    private var lastFailureAt: Long? = null

    suspend fun refresh(skip: () -> Boolean = { false }, probe: suspend () -> Boolean): Boolean = mutex.withLock {
        if (skip()) return@withLock false
        val elapsed = lastFailureAt?.let { nowMs() - it }
        if (elapsed != null && elapsed in 0 until failureCooldownMs) return@withLock false
        // Cancellation is not a failed server check and must not poison the next attempt.
        val healthy = probe()
        currentCoroutineContext().ensureActive()
        lastFailureAt = if (healthy) null else nowMs()
        healthy
    }
}
