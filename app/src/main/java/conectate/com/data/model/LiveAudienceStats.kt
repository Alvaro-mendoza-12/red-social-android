package conectate.com.data.model

import java.util.Locale

/** Audience observed during this device's live session, including reconnects. */
data class LiveAudienceStats(
    val peakConcurrent: Int = 0,
    val seenViewerIds: Set<String> = emptySet()
) {
    val uniqueViewers: Int get() = seenViewerIds.size

    fun withViewerCount(count: Int): LiveAudienceStats =
        copy(peakConcurrent = maxOf(peakConcurrent, count.coerceAtLeast(0)))

    fun withViewers(viewers: List<LiveViewerParticipant>): LiveAudienceStats = copy(
        peakConcurrent = maxOf(peakConcurrent, viewers.size),
        seenViewerIds = seenViewerIds + viewers.mapNotNull {
            it.userId.trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank)
                ?: it.socketId.trim().takeIf(String::isNotBlank)?.let { socket -> "socket:$socket" }
        }
    )
}
