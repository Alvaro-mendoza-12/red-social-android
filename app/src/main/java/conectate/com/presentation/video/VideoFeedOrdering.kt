package conectate.com.presentation.video

import conectate.com.data.model.Video
import conectate.com.data.repository.stableAccountVideoOrder

/**
 * Orders only the presentation of a recommendation page.
 *
 * The server remains responsible for eligibility, privacy and personalization.
 * This session order prevents a cached/algorithmically tied page from looking
 * identical every time the Videos tab is reopened, while remaining stable for
 * the lifetime of one feed session (so recomposition cannot reshuffle a video
 * under the user's finger).
 */
internal fun orderVideosForFeedSession(
    videos: Iterable<Video>,
    accountId: String?,
    sessionId: String,
    previousOrder: List<String> = emptyList(),
    preserveSourceOrder: Boolean = false,
): List<Video> {
    val uniqueVideos = videos.distinctBy { it.id.trim().lowercase() }
    if (uniqueVideos.size < 2) return uniqueVideos

    val sessionSeed = "${accountId?.trim()?.lowercase().orEmpty().ifBlank { "guest" }}:$sessionId"
    val ordered = if (preserveSourceOrder) {
        uniqueVideos
    } else {
        uniqueVideos.sortedWith(
            compareByDescending<Video> { stableAccountVideoOrder(sessionSeed, it.id) }
                .thenBy { it.id.trim().lowercase() },
        )
    }

    // A new session must not start with the same first video when the backend
    // returned the same candidates. A one-position rotation is deterministic
    // and avoids a visible repeat without introducing per-recomposition randomness.
    val currentOrder = ordered.map { it.id.trim().lowercase() }
    if (
        previousOrder.size > 1 &&
        currentOrder.firstOrNull() == previousOrder.firstOrNull()
    ) {
        return ordered.drop(1) + ordered.first()
    }
    return ordered
}
