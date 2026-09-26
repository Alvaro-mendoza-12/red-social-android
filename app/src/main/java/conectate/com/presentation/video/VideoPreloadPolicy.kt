package conectate.com.presentation.video

internal const val DEFAULT_VISIBLE_VIDEO_BUFFER_BEFORE_PRELOAD_MS = 5_000L

/**
 * Protects the visible stream before a neighbour starts consuming bandwidth.
 * Short videos are safe once their complete remaining duration is buffered.
 */
internal fun hasProtectedVisibleVideoBuffer(
    currentMediaId: String?,
    selectedVideoId: String?,
    isReady: Boolean,
    isPlaying: Boolean,
    durationMs: Long,
    bufferedPositionMs: Long,
    currentPositionMs: Long,
    minimumBufferedAheadMs: Long = DEFAULT_VISIBLE_VIDEO_BUFFER_BEFORE_PRELOAD_MS,
): Boolean {
    if (
        selectedVideoId.isNullOrBlank() ||
        !currentMediaId.equals(selectedVideoId, ignoreCase = true) ||
        !isReady ||
        !isPlaying
    ) return false

    val fullyBuffered = durationMs > 0L && bufferedPositionMs >= durationMs - 250L
    val bufferedAheadMs = (bufferedPositionMs - currentPositionMs).coerceAtLeast(0L)
    return fullyBuffered || bufferedAheadMs >= minimumBufferedAheadMs
}
