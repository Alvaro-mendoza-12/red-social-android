package conectate.com.data.repository

/** Metadata refreshes share a short budget; local stickers stay immediately usable. */
internal class StickerRefreshGate(private val intervalMs: Long = 30_000L) {
    internal data class Lease(val owner: String, val generation: Long)
    private var generation = 0L
    private var active: Lease? = null
    private var lastOwner: String? = null
    private var lastStarted = 0L

    @Synchronized fun claim(owner: String, now: Long): Lease? {
        if (owner.isBlank() || active?.owner == owner) return null
        if (lastOwner == owner && now >= lastStarted && now - lastStarted < intervalMs) return null
        lastOwner = owner
        lastStarted = now
        return Lease(owner, ++generation).also { active = it }
    }

    @Synchronized fun finish(lease: Lease) {
        if (active == lease) active = null
    }
}
