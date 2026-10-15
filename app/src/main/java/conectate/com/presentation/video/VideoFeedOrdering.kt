package conectate.com.presentation.video

import conectate.com.data.model.Video
import conectate.com.data.repository.stableAccountVideoOrder

/** A late personalized page may reorder unseen videos, but never the current position. */
internal fun mergeRankedPageAfterDisplayedPrefix(
    currentPage: List<Video>, rankedPage: List<Video>, displayedIds: Set<String>,
): List<Video> {
    val lastDisplayedIndex = currentPage.indexOfLast { it.id.trim().lowercase() in displayedIds }
    val stablePrefix = currentPage.take(lastDisplayedIndex + 1)
    return (stablePrefix + rankedPage).distinctBy { it.id.trim().lowercase() }
}

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
    seenIds: Set<String> = emptySet(),
    recentIds: List<String> = emptyList(),
): List<Video> {
    val uniqueVideos = conectate.com.core.feed.prioritizeFeedCandidates(videos, emptySet()) { it.id }
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

    return conectate.com.core.feed.prioritizeFeedCandidates(
        ordered, seenIds, previousOrder.firstOrNull(), recentIds = recentIds,
    ) { it.id }
}
