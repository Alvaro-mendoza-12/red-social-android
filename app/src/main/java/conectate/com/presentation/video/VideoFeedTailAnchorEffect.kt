package conectate.com.presentation.video

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import conectate.com.core.feed.canonicalFeedId
import conectate.com.data.model.Video
import kotlinx.coroutines.flow.collect

private data class VideoFeedTailObservation(
    val sessionId: String?,
    val videoIds: List<String>,
    val focusedStableKey: String?,
    val scrolling: Boolean,
    val enabled: Boolean,
)

@Composable
internal fun MaintainVideoFeedTailAppendAnchor(
    pagerState: PagerState,
    videos: List<Video>,
    entries: List<VideoFeedEntry>,
    sessionId: String?,
    enabled: Boolean,
) {
    val videoIds = remember(videos) { videos.mapNotNull { canonicalFeedId(it.id) }.distinct() }
    val latestVideoIds = rememberUpdatedState(videoIds)
    val latestEntries = rememberUpdatedState(entries)
    val latestSessionId = rememberUpdatedState(sessionId)
    val latestEnabled = rememberUpdatedState(enabled)
    val anchor = remember(pagerState) { VideoFeedTailAppendAnchor() }
    LaunchedEffect(pagerState, anchor) {
        snapshotFlow {
            VideoFeedTailObservation(
                latestSessionId.value,
                latestVideoIds.value,
                // Use the last measured key, which remains valid before the next
                // layout. Looking up a new list with an old index can select a rail.
                pagerState.layoutInfo.visiblePagesInfo
                    .firstOrNull { it.index == pagerState.currentPage }?.key as? String,
                pagerState.isScrollInProgress,
                latestEnabled.value,
            )
        }.collect { observation ->
            val targetId = anchor.observe(
                observation.sessionId, observation.videoIds, observation.focusedStableKey,
                observation.scrolling, observation.enabled,
            ) ?: return@collect
            val targetPage = latestEntries.value.indexOfFirst {
                it is VideoFeedVideoEntry && canonicalFeedId(it.video.id) == targetId
            }
            if (targetPage >= 0) pagerState.requestScrollToPage(targetPage)
        }
    }
}
