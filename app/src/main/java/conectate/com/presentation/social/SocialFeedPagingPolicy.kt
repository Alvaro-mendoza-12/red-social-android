package conectate.com.presentation.social

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import conectate.com.data.model.Post
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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

@Composable
internal fun SocialFeedViewportEffect(
    listState: LazyListState,
    active: Boolean,
    accountId: String?,
    posts: List<Post>,
    onImpression: (Post) -> Unit,
    onVisibleAuthors: (List<String>) -> Unit,
) {
    val postsById by rememberUpdatedState(remember(posts) { posts.associateBy(Post::id) })
    val recordImpression by rememberUpdatedState(onImpression)
    val updateAuthors by rememberUpdatedState(onVisibleAuthors)
    LaunchedEffect(listState, active, accountId) {
        if (!active) return@LaunchedEffect
        var previousIds = emptySet<String>()
        snapshotFlow {
            val currentPosts = postsById
            listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                socialPostIdFromListKey(item.key)?.let(currentPosts::get)?.let { it.id to it.userId }
            }
        }.collectLatest { visible ->
            // Changes to post fields such as reaction counts leave this viewport unchanged.
            visible.forEach { (id, _) ->
                if (id !in previousIds) postsById[id]?.let(recordImpression)
            }
            previousIds = visible.mapTo(mutableSetOf()) { it.first }
            if (visible.isEmpty()) return@collectLatest
            delay(250)
            updateAuthors(visible.map { it.second }.distinct())
        }
    }
}
