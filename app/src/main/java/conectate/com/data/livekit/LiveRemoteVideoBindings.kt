package conectate.com.data.livekit

/** The SFU can retain a track SID while replacing its native receiving transport. */
internal class LiveRemoteVideoBindings {
    private var serial = 0L
    private val versions = mutableMapOf<String, Long>()

    @Synchronized fun subscribed(trackId: String) {
        if (trackId.isNotBlank()) versions[trackId] = ++serial
    }
    @Synchronized fun reconnected(trackIds: Iterable<String>) {
        trackIds.forEach { subscribed(it) }
    }
    @Synchronized fun version(trackId: String): Long =
        if (trackId.isBlank()) 0L else versions.getOrPut(trackId) { ++serial }

    @Synchronized fun retain(trackIds: Set<String>) {
        versions.keys.retainAll(trackIds)
    }
    @Synchronized fun clear() { versions.clear() }
}
