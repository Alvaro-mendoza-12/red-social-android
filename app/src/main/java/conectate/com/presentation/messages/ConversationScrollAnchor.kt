package conectate.com.presentation.messages

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun <T> initialConversationScrollIndex(
    messages: List<T>,
    focusMessageId: String?,
    messageId: (T) -> String,
): Int = if (focusMessageId.isNullOrBlank()) messages.lastIndex
    else messages.indexOfFirst { messageId(it).equals(focusMessageId, ignoreCase = true) }

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
    var initialPositionRequested by remember(conversationKey, focusMessageId) { mutableStateOf(false) }
    val scrollMutex = remember(conversationKey) { Mutex() }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val currentlyDragging by rememberUpdatedState(dragging)

    // Request the first position before LazyColumn measures this new message set.
    // This prevents briefly drawing the oldest message and then visibly jumping.
    SideEffect {
        if (!initialPositionRequested && messages.isNotEmpty()) {
            val initialIndex = initialConversationScrollIndex(messages, focusMessageId, messageId)
            if (initialIndex >= 0) {
                listState.requestScrollToItem(initialIndex)
                initialPositionRequested = true
            }
        }
    }

    LaunchedEffect(conversationKey, listState) {
        // Observe scrolling outside composition. A fling should not restart the
        // anchor's effects or recompose this scope on every layout update.
        snapshotFlow { Triple(currentlyDragging, listState.isScrollInProgress, listState.canScrollForward) }
            .distinctUntilChanged()
            .collect { (dragged, scrolling, canScrollForward) ->
                if (dragged || (scrolling && !scrollMutex.isLocked)) followingLatest = !canScrollForward
            }
    }

    suspend fun scrollToLatest() = scrollMutex.withLock {
        // Wait for the list to measure the new message/viewport before anchoring it.
        withFrameNanos { }
        // A keyboard resize or new message must not cancel an active native
        // drag/fling through scrollToItem's higher-priority scroll mutation.
        if (currentlyDragging || listState.isScrollInProgress) return@withLock
        val lastIndex = latestMessages.lastIndex
        if (lastIndex < 0) return@withLock
        listState.scrollToItem(lastIndex)
        withFrameNanos { }
        if (currentlyDragging || listState.isScrollInProgress) return@withLock
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
        var previousViewport: Int? = null
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            // imePadding changes the viewport several times during keyboard animation.
            // Re-anchor once after it settles instead of issuing a scroll per frame.
            .collectLatest { viewport ->
                delay(120)
                val changed = previousViewport != null && viewport != previousViewport
                previousViewport = viewport
                if (changed && followingLatest && focusResolved && !currentlyDragging && !listState.isScrollInProgress &&
                    latestMessages.isNotEmpty()) scrollToLatest()
        }
    }
}
