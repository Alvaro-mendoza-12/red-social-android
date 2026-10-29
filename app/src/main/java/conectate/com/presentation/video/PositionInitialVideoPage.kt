package conectate.com.presentation.video

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** Entry loading hides the pager, so positioning must not wait for its first layout. */
@Composable
internal fun PositionInitialVideoPage(
    videoId: String?, entries: List<VideoFeedEntry>, pager: PagerState,
    applied: Boolean, onApplied: () -> Unit,
) {
    LaunchedEffect(videoId, entries, applied) {
        if (applied) return@LaunchedEffect
        val target = videoId?.trim()?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        val page = entries.indexOfFirst { it is VideoFeedVideoEntry && it.video.id.equals(target, true) }
        if (page >= 0) {
            // scrollToPage suspends until layout, which cannot happen behind the loading gate.
            pager.requestScrollToPage(page)
            onApplied()
        }
    }
}
