package conectate.com.data.voice

enum class VoiceCue { JOIN, LEAVE, MUTE, UNMUTE }
data class CueParticipant(val userId: String, val socketId: String, val isMuted: Boolean)

/** Authenticated signal transitions; snapshots establish a silent baseline. */
class VoiceRoomCuePolicy(private val selfId: String) {
    private var connected = false
    private var ready = false
    private var greeted = false
    private var selfMuted = true
    private val peers = mutableMapOf<String, CueParticipant>()
    private val leaving = mutableMapOf<String, Long>()
    @Synchronized fun connect(muted: Boolean): List<VoiceCue> {
        connected = true; selfMuted = muted
        if (greeted) return emptyList()
        greeted = true; return listOf(VoiceCue.JOIN)
    }
    @Synchronized fun baseline(participants: List<CueParticipant>) {
        peers.clear(); participants.filter { it.userId.isNotBlank() && it.userId != selfId }.forEach { peers[it.userId] = it }
        leaving.clear(); ready = true
    }
    @Synchronized fun join(peer: CueParticipant): List<VoiceCue> {
        if (!connected || !ready || peer.userId.isBlank() || peer.userId == selfId) return emptyList()
        val known = peers.containsKey(peer.userId)
        val rejoining = leaving.remove(peer.userId) != null
        peers[peer.userId] = peer
        return if (known || rejoining) emptyList() else listOf(VoiceCue.JOIN)
    }
    @Synchronized fun leave(socketId: String, now: Long) {
        if (!connected || !ready) return
        peers.values.filter { it.socketId == socketId }.toList().forEach { peers.remove(it.userId); leaving[it.userId] = now + 700 }
    }
    @Synchronized fun drain(now: Long): List<VoiceCue> {
        if (!connected || !ready) return emptyList()
        val expired = leaving.filterValues { it <= now }.keys.toList()
        expired.forEach(leaving::remove)
        return expired.map { VoiceCue.LEAVE }
    }
    @Synchronized fun mute(userId: String, muted: Boolean): List<VoiceCue> {
        if (!connected) return emptyList()
        if (userId == selfId) { if (muted == selfMuted) return emptyList(); selfMuted = muted }
        else {
            val peer = peers[userId] ?: return emptyList()
            if (!ready || peer.isMuted == muted) return emptyList()
            peers[userId] = peer.copy(isMuted = muted)
        }
        return listOf(if (muted) VoiceCue.MUTE else VoiceCue.UNMUTE)
    }
    @Synchronized fun suspend() { connected = false; ready = false; peers.clear(); leaving.clear() }
    @Synchronized fun leaveSelf(): List<VoiceCue> {
        val wasConnected = connected; suspend(); greeted = false
        return if (wasConnected) listOf(VoiceCue.LEAVE) else emptyList()
    }
}
