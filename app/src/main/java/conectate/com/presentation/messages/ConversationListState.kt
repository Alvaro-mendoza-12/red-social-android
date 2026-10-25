package conectate.com.presentation.messages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable

/** Prepare nearby rows before a fling reaches them, without retaining the full history. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberConversationListState(): LazyListState = rememberLazyListState(
    cacheWindow = LazyLayoutCacheWindow(aheadFraction = 0.5f, behindFraction = 0.25f),
)
