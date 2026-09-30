package conectate.com.data.repository

/** Keeps native start callbacks atomic with the latest guest controls and departure. */
internal class LiveGuestMediaStartCoordinator {
    @ConsistentCopyVisibility
    data class Attempt internal constructor(val generation: Long, val liveId: String)

    private val lock = Any()
    private var generation = 0L
    private var pending: Attempt? = null

    fun <T> withLock(action: () -> T): T = synchronized(lock) { action() }

    fun begin(liveId: String): Attempt = synchronized(lock) {
        generation += 1
        Attempt(generation, liveId).also { pending = it }
    }

    fun isStarting(liveId: String): Boolean = synchronized(lock) {
        pending?.liveId == liveId
    }

    fun invalidate() = synchronized(lock) {
        generation += 1
        pending = null
    }

    fun completeIfCurrent(
        attempt: Attempt,
        eligible: () -> Boolean = { true },
        commit: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (pending != attempt || generation != attempt.generation) return@synchronized false
        pending = null
        if (!eligible()) return@synchronized false
        commit()
        true
    }
}

/** Ignore repeated snapshots, but recover new/replaced peers and local reconnections. */
internal fun shouldConnectLiveGuestPeer(
    socketId: String,
    localSocketId: String?,
    previousSocketIds: Set<String>,
    localSocketChanged: Boolean,
): Boolean = socketId.isNotBlank() && socketId != localSocketId &&
    (localSocketChanged || socketId !in previousSocketIds)
