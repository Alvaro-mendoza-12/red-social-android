package conectate.com.presentation.messages

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep the latest message above the composer as the keyboard/panel changes size. */
@Composable
internal fun <T> ConversationScrollAnchor(
    conversationKey: String,
    messages: List<T>,
    currentUserId: String,
    focusMessageId: String?,
    listState: LazyListState,
    messageId: (T) -> String,
    senderId: (T) -> String
) {
    val latestMessages by rememberUpdatedState(messages)
    var previousLastId by remember(conversationKey) { mutableStateOf<String?>(null) }
    var followingLatest by remember(conversationKey) { mutableStateOf(true) }
    var focusResolved by remember(conversationKey, focusMessageId) { mutableStateOf(focusMessageId.isNullOrBlank()) }
    val scrollMutex = remember(conversationKey) { Mutex() }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val currentlyDragging by rememberUpdatedState(dragging)
    val canScrollForward = listState.canScrollForward

    LaunchedEffect(dragging, canScrollForward, listState.isScrollInProgress) {
        if (dragging || (listState.isScrollInProgress && !scrollMutex.isLocked)) {
            followingLatest = !canScrollForward
        }
    }

    suspend fun scrollToLatest() = scrollMutex.withLock {
        // Wait for the list to measure the new message/viewport before anchoring it.
        withFrameNanos { }
        val lastIndex = latestMessages.lastIndex
        if (lastIndex < 0) return@withLock
        listState.scrollToItem(lastIndex)
        withFrameNanos { }
        val tail = listState.layoutInfo.visibleItemsInfo.lastOrNull()
        listState.scrollBy((tail?.size ?: 0).toFloat())
    }

    LaunchedEffect(conversationKey, messages.lastOrNull()?.let(messageId), messages.size, focusMessageId) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!focusResolved) {
            val index = messages.indexOfFirst { messageId(it).equals(focusMessageId, ignoreCase = true) }
            if (index < 0) return@LaunchedEffect
            followingLatest = false
            withFrameNanos { }
            listState.scrollToItem(index)
            focusResolved = true
            previousLastId = messageId(messages.last())
            return@LaunchedEffect
        }
        val latest = messages.last()
        if (shouldScrollChatToLatest(previousLastId, messageId(latest), senderId(latest), followingLatest, currentUserId)) {
            followingLatest = true
            scrollToLatest()
        }
        previousLastId = messageId(latest)
    }

    LaunchedEffect(conversationKey, listState) {
        var previousViewport = -1
        var previousCount = -1
        snapshotFlow {
            val layout = listState.layoutInfo
            Triple(layout.viewportSize.height, layout.totalItemsCount,
                layout.visibleItemsInfo.lastOrNull()?.let { it.key to it.size })
        }.distinctUntilChanged().collect { (viewport, count, tail) ->
            val viewportChanged = viewport != previousViewport
            val countChanged = count != previousCount
            previousViewport = viewport
            previousCount = count
            val tailIsLatest = tail?.first == latestMessages.lastOrNull()?.let(messageId)
            if ((viewportChanged || countChanged || tailIsLatest) && followingLatest &&
                focusResolved && !currentlyDragging && latestMessages.isNotEmpty()) scrollToLatest()
        }
    }
}
