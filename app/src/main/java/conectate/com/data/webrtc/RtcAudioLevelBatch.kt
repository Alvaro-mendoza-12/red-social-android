package conectate.com.data.webrtc

/** Collects native callbacks without blocking the negotiation executor. */
internal class RtcAudioLevelBatch(
    private val peerIds: Set<String>,
    private val callback: (Map<String, Double>) -> Unit
) {
    private val lock = Any()
    private val answered = mutableSetOf<String>()
    private val levels = mutableMapOf<String, Double>()
    private var delivered = false

    fun accept(peerId: String, level: Double) {
        val result = synchronized(lock) {
            if (delivered || peerId !in peerIds || !answered.add(peerId)) return
            if (level.isFinite() && level > 0.0) levels[peerId] = level.coerceAtMost(1.0)
            if (answered.size == peerIds.size) snapshotLocked() else null
        }
        result?.let(callback)
    }

    fun finish() {
        val result = synchronized(lock) { if (delivered) null else snapshotLocked() }
        result?.let(callback)
    }

    private fun snapshotLocked(): Map<String, Double> {
        delivered = true
        return levels.toMap()
    }
}
