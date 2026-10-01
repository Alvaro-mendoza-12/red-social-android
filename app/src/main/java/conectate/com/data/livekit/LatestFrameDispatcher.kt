package conectate.com.data.livekit

import java.util.concurrent.Executor

/** Owns one pending frame and one in-flight frame; old pending frames never form a latency queue. */
internal class LatestFrameDispatcher<T : Any>(
    private val executor: Executor,
    private val process: (T) -> Unit,
    private val release: (T) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var pending: T? = null
    private var draining = false
    private var closed = false

    /** Transfers ownership, including when closed or the worker rejects the task. */
    fun submit(frame: T) {
        var schedule = false
        val displaced = synchronized(lock) {
            if (closed) frame else pending.also {
                pending = frame
                if (!draining) { draining = true; schedule = true }
            }
        }
        displaced?.let(::releaseSafely)
        if (schedule) try {
            executor.execute(::drain)
        } catch (error: Exception) {
            close()
            synchronized(lock) { draining = false }
            onFailure(error)
        }
    }

    fun clearPending() {
        val displaced = synchronized(lock) { pending.also { pending = null } }
        displaced?.let(::releaseSafely)
    }

    fun close() {
        val displaced = synchronized(lock) {
            closed = true
            pending.also { pending = null }
        }
        displaced?.let(::releaseSafely)
    }

    private fun drain() {
        while (true) {
            val frame = synchronized(lock) {
                pending.also {
                    pending = null
                    if (it == null) draining = false
                }
            } ?: return
            try { process(frame) } catch (error: Exception) { onFailure(error) }
            finally { releaseSafely(frame) }
        }
    }

    private fun releaseSafely(frame: T) {
        try { release(frame) } catch (error: Exception) { onFailure(error) }
    }
}
