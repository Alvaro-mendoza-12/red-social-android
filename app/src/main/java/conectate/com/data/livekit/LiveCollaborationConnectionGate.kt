package conectate.com.data.livekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes credentials, SDK publication and server confirmation as one attempt. */
internal class LiveCollaborationConnectionGate {
    private val mutex = Mutex()
    private val stateLock = Any()
    private var generation = 0L
    private var activeJob: Job? = null

    inner class Attempt internal constructor(private val version: Long) {
        suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (synchronized(stateLock) { generation != version }) {
                throw CancellationException("Live collaboration attempt superseded")
            }
        }
    }

    suspend fun <T> runLatest(block: suspend Attempt.() -> T): T {
        currentCoroutineContext().ensureActive()
        val owner = currentCoroutineContext()[Job]
            ?: error("Live collaboration requires a coroutine job")
        val (version, previous) = synchronized(stateLock) {
            generation += 1
            (generation to activeJob).also { activeJob = owner }
        }
        if (previous !== owner) previous?.cancel(CancellationException("New live collaboration attempt"))
        try {
            return mutex.withLock {
                val attempt = Attempt(version)
                attempt.ensureCurrent()
                attempt.block()
            }
        } finally {
            synchronized(stateLock) { if (activeJob === owner) activeJob = null }
        }
    }

    fun invalidate(): Long {
        val (version, previous) = synchronized(stateLock) {
            generation += 1
            (generation to activeJob).also { activeJob = null }
        }
        previous?.cancel(CancellationException("Left live collaboration"))
        return version
    }

    fun snapshot(): Long = synchronized(stateLock) { generation }

    suspend fun serialized(block: suspend () -> Unit) = mutex.withLock { block() }

    /** An older deferred disconnect must never close a newer connection. */
    suspend fun ifLatest(version: Long, block: suspend () -> Unit) = mutex.withLock {
        if (synchronized(stateLock) { generation == version }) block()
    }
}
