package conectate.com.presentation.social

internal const val MIN_IMAGE_MOMENT_PLAYBACK_MS = 6_000L
internal const val MAX_IMAGE_MOMENT_PLAYBACK_MS = 60_000L

/** Separate soundtrack must stop with a stalled/ended video, not outlive its picture. */
internal fun momentSoundtrackClockRunning(requested: Boolean, isVideo: Boolean, videoPlaying: Boolean): Boolean =
    requested && (!isVideo || videoPlaying)

/** Keeps still-image Moment timing independent from decoder or frame speed. */
internal fun resolvedImageMomentPlaybackDurationMs(
    @Suppress("UNUSED_PARAMETER") requestedDurationMs: Long?,
    @Suppress("UNUSED_PARAMETER") selectedMusicDurationMs: Long?,
): Long = MIN_IMAGE_MOMENT_PLAYBACK_MS

/** Remaining wall-clock interval; independent from Android's animator scale. */
internal fun imageMomentRemainingDurationMs(durationMs: Long, progress: Float): Long {
    val safeDuration = durationMs.coerceAtLeast(MIN_IMAGE_MOMENT_PLAYBACK_MS)
    val safeProgress = progress.coerceIn(0f, 1f)
    return kotlin.math.ceil(safeDuration * (1.0 - safeProgress.toDouble()))
        .toLong()
        .coerceAtLeast(0L)
}

/** Progress computed from Android's monotonic elapsed-realtime clock. */
internal fun imageMomentProgressAt(
    initialProgress: Float,
    startedAtMs: Long,
    nowMs: Long,
    durationMs: Long,
): Float {
    val safeDuration = durationMs.coerceAtLeast(MIN_IMAGE_MOMENT_PLAYBACK_MS)
    val safeInitial = initialProgress.coerceIn(0f, 1f)
    val elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L)
    return (safeInitial + elapsedMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
}
