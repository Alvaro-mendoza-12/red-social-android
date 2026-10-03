package conectate.com.presentation.social

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun SocialFeedPagingEffect(listState: LazyListState, active: Boolean,
    hasMore: Boolean, refreshing: Boolean, loadingMore: Boolean, hasError: Boolean,
    resetScroll: Long, onLoadMore: () -> Unit) {
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(resetScroll) { listState.scrollToItem(0) }
    LaunchedEffect(listState, active, hasMore, refreshing, loadingMore, hasError) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            shouldLoadMoreSocialPosts(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                listState.layoutInfo.totalItemsCount, hasMore, refreshing, loadingMore, hasError)
        }.distinctUntilChanged().collect { if (it) loadMore() }
    }
}

internal fun shouldLoadMoreSocialPosts(lastVisibleIndex: Int?, totalItems: Int,
    hasMore: Boolean, refreshing: Boolean, loadingMore: Boolean, hasError: Boolean): Boolean =
    hasMore && !refreshing && !loadingMore && !hasError && lastVisibleIndex != null &&
        totalItems > 0 && lastVisibleIndex >= (totalItems - 5).coerceAtLeast(0)
