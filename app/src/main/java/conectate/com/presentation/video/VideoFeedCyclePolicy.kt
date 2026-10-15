package conectate.com.presentation.video

import conectate.com.data.model.Video
import conectate.com.core.feed.prioritizeFeedCandidates

internal fun continuedVideoOrder(
    videos: List<Video>, seenIds: Set<String>, lastDisplayed: String?,
    recentIds: List<String> = emptyList(), previousFirst: String? = null,
): List<Video> = prioritizeFeedCandidates(
    videos, seenIds, previousFirst ?: lastDisplayed, recentIds = recentIds,
) { it.id }
