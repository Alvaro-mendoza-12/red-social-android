package conectate.com.presentation.video

import conectate.com.data.model.Video

internal data class VideoFeedbackBlock(val dimension: String, val subjectKey: String) {
    fun matches(video: Video): Boolean = when (dimension) {
        "content" -> video.id.equals(subjectKey, ignoreCase = true)
        "creator" -> video.userId.equals(subjectKey, ignoreCase = true)
        "topic" -> video.tags.any { VideoAlgorithmFeedbackPolicy.topicKey(it) == subjectKey }
        "format" -> VideoAlgorithmFeedbackPolicy.formatKey(video.duration) == subjectKey
        else -> false
    }
}

/**
 * Normalises feedback values before they become an algorithm rule.
 *
 * A format must describe a real subset of videos.  The legacy value
 * `video` means the whole video surface and is therefore never a valid
 * negative format preference.
 */
internal object VideoAlgorithmFeedbackPolicy {
    private val genericTopics = setOf(
        "all",
        "any",
        "general",
        "unknown",
        "misc",
        "video-general",
        "social-general",
    )

    fun topicKey(raw: String): String? = raw.trim().lowercase()
        .takeIf { it.length >= 2 && it !in genericTopics }

    fun formatKey(durationSeconds: Long): String =
        if (durationSeconds in 1..60) "short-video" else "long-video"

    /**
     * Never let a stale or over-broad personalized rule turn the first screen
     * into an empty feed. In that case the caller must use the public legacy
     * feed as a safe recovery path.
     */
    fun shouldUseAuthoritativeInitialPage(itemCount: Int): Boolean = itemCount > 0
}
