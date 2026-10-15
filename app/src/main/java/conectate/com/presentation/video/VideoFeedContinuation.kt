package conectate.com.presentation.video

import conectate.com.core.feed.canonicalFeedId

/** UI acceptance also describes empty legacy continuation; errors follow the active data source. */
internal fun videoFeedActiveSourceError(authoritativeActive: Boolean, catalogError: String?, pageError: String?): String? =
    if (authoritativeActive) pageError else catalogError

/** Preserve video keys. Only someone on the tail is advanced into newly delivered videos. */
internal class VideoFeedTailAppendAnchor {
    private var previousSessionId: String? = null
    private var previousVideoIds: List<String> = emptyList()
    private var pendingVideoId: String? = null

    fun observe(
        sessionId: String?,
        videoIds: List<String>,
        focusedStableKey: String?,
        scrolling: Boolean,
        enabled: Boolean = true,
    ): String? {
        val currentIds = videoIds.mapNotNull(::canonicalFeedId).distinct()
        if (!enabled || sessionId == null) {
            previousSessionId = null
            previousVideoIds = emptyList()
            pendingVideoId = null
            return null
        }
        if (previousSessionId != sessionId) {
            previousSessionId = sessionId
            previousVideoIds = currentIds
            pendingVideoId = null
            return null
        }
        val onTail = focusedStableKey == VideoFeedTailEntry.stableKey
        if (currentIds != previousVideoIds) {
            if (onTail && previousVideoIds.isNotEmpty() && pendingVideoId == null) {
                val previous = previousVideoIds.toSet()
                pendingVideoId = currentIds.firstOrNull { it !in previous }
            }
            previousVideoIds = currentIds
        }
        // Let the gesture settle. Leaving the tail cancels its pending admission.
        if (scrolling) return null
        if (!onTail) {
            pendingVideoId = null
            return null
        }
        return pendingVideoId?.takeIf { it in currentIds }.also { pendingVideoId = null }
    }
}
