package conectate.com.presentation.video

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState

@Composable
internal fun ContinueRecommendedFeedAtTail(pager: PagerState, entries: List<VideoFeedEntry>,
    sessionId: String?, loading: Boolean, error: String?, enabled: Boolean, onContinue: () -> Unit) {
    val latest = rememberUpdatedState(onContinue)
    LaunchedEffect(pager.settledPage, pager.isScrollInProgress, sessionId, loading, error, enabled) {
        if (enabled && !pager.isScrollInProgress && !loading && error == null &&
            entries.getOrNull(pager.settledPage) == VideoFeedTailEntry) latest.value()
    }
}
