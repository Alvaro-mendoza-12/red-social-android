package conectate.com.presentation.video

/**
 * Counts only short, continuous samples of playback that ExoPlayer reports as actually playing.
 * The tracker intentionally survives list refreshes and recompositions in its owning ViewModel.
 */
internal class RealPlaybackViewTracker(
    private val thresholdMs: Long = DEFAULT_VIEW_THRESHOLD_MS,
    private val maxContinuousSampleGapMs: Long = DEFAULT_MAX_SAMPLE_GAP_MS
) {
    private data class Progress(
        var playedMs: Long = 0L,
        var lastSampleMs: Long? = null,
        var wasPlaying: Boolean = false,
        var recordRequested: Boolean = false
    )

    private val progressByVideo = mutableMapOf<String, Progress>()
    private var activeVideoId: String? = null

    init {
        require(thresholdMs > 0L)
        require(maxContinuousSampleGapMs > 0L)
    }

    @Synchronized
    fun sample(videoId: String?, isActuallyPlaying: Boolean, elapsedRealtimeMs: Long): Boolean {
        val cleanVideoId = videoId.canonicalVideoId()
        if (cleanVideoId == null) {
            deactivateCurrentVideo(elapsedRealtimeMs)
            return false
        }

        if (activeVideoId != cleanVideoId) {
            deactivateCurrentVideo(elapsedRealtimeMs)
            activeVideoId = cleanVideoId
        }

        val progress = progressByVideo.getOrPut(cleanVideoId) { Progress() }
        val elapsedSinceLastSample = progress.lastSampleMs?.let { elapsedRealtimeMs - it }
        if (
            progress.wasPlaying &&
            isActuallyPlaying &&
            elapsedSinceLastSample != null &&
            elapsedSinceLastSample in 0L..maxContinuousSampleGapMs
        ) {
            progress.playedMs += elapsedSinceLastSample
        }

        progress.lastSampleMs = elapsedRealtimeMs
        progress.wasPlaying = isActuallyPlaying

        return if (
            isActuallyPlaying &&
            progress.playedMs >= thresholdMs &&
            !progress.recordRequested
        ) {
            progress.recordRequested = true
            true
        } else {
            false
        }
    }

    @Synchronized
    fun allowRetry(videoId: String) {
        val cleanVideoId = videoId.canonicalVideoId() ?: return
        progressByVideo[cleanVideoId]?.recordRequested = false
    }

    @Synchronized
    internal fun playedMs(videoId: String): Long {
        val cleanVideoId = videoId.canonicalVideoId() ?: return 0L
        return progressByVideo[cleanVideoId]?.playedMs ?: 0L
    }

    private fun deactivateCurrentVideo(elapsedRealtimeMs: Long) {
        activeVideoId?.let { currentId ->
            progressByVideo[currentId]?.apply {
                lastSampleMs = elapsedRealtimeMs
                wasPlaying = false
            }
        }
        activeVideoId = null
    }

    private fun String?.canonicalVideoId(): String? =
        this?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private companion object {
        // Se cuenta la vista casi al iniciar la reproducción (estilo TikTok): basta ~0.5s de
        // reproducción real y continua. El umbral bajo (no 0) evita inflar vistas por el
        // scroll rápido en el que un vídeo aparece unas milésimas sin llegar a reproducirse.
        const val DEFAULT_VIEW_THRESHOLD_MS = 500L
        const val DEFAULT_MAX_SAMPLE_GAP_MS = 1_000L
    }
}
