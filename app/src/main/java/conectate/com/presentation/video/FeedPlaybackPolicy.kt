package conectate.com.presentation.video

internal fun shouldPrefetchFeedVideo(connected: Boolean, dataSaver: Boolean, resumed: Boolean, overlayOpen: Boolean): Boolean =
    connected && !dataSaver && resumed && !overlayOpen

internal fun shouldPlayFeedVideo(
    requested: Boolean,
    videoId: String?,
    isCellular: Boolean,
    autoplayOnMobile: Boolean,
    manuallyStartedVideoId: String?,
): Boolean = requested && videoId != null &&
    (!isCellular || autoplayOnMobile || manuallyStartedVideoId == videoId)

internal data class VideoReviewLoop(val videoId: String, val startMs: Long, val endMs: Long) {
    companion object {
        fun at(videoId: String, positionMs: Long, durationMs: Long): VideoReviewLoop? {
            if (videoId.isBlank() || durationMs < 1_000L) return null
            val start = positionMs.coerceIn(0L, durationMs - 1_000L)
            return VideoReviewLoop(videoId, start, (start + 10_000L).coerceAtMost(durationMs))
        }
    }
}

/** Counts foreground playback, excluding pauses, buffering and gaps after app backgrounding. */
internal class VideoWatchBudget {
    var watchedMs: Long = 0L
        private set
    private var previousTimeMs: Long? = null
    private var previouslyPlaying = false

    fun sample(nowMs: Long, isPlaying: Boolean): Long {
        val elapsed = previousTimeMs?.let { nowMs - it } ?: 0L
        if (isPlaying && previouslyPlaying && elapsed in 1L..1_500L) watchedMs += elapsed
        previousTimeMs = nowMs
        previouslyPlaying = isPlaying
        return watchedMs
    }

    fun reset() {
        watchedMs = 0L
        previousTimeMs = null
        previouslyPlaying = false
    }
}

internal fun videoTimeLabel(positionMs: Long): String {
    val seconds = positionMs.coerceAtLeast(0L) / 1_000L
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
