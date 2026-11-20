package conectate.com.presentation.video

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/** Resume once after yielding bandwidth to the selected video, within the original time budget. */
internal suspend fun preloadFeedNeighbours(
    urls: List<String>,
    timeBudgetMs: Long,
    hasBuffer: (starting: Boolean) -> Boolean,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    preload: suspend (url: String, timeoutMs: Long) -> Unit,
) = coroutineScope {
    for (url in urls) {
        var remainingMs = timeBudgetMs
        for (attempt in 1..2) {
            if (remainingMs <= 0L) break
            while (!hasBuffer(true)) delay(100L)
            val startedAt = nowMs()
            val budget = remainingMs
            val read = launch { preload(url, budget) }
            var interruptedForPlayback = false
            while (read.isActive) {
                withTimeoutOrNull(120L) { read.join() }
                if (read.isActive && !hasBuffer(false)) {
                    interruptedForPlayback = true
                    read.cancelAndJoin()
                }
            }
            if (!interruptedForPlayback) break
            remainingMs -= nowMs() - startedAt
        }
    }
}
