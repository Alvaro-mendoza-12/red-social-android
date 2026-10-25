package conectate.com.presentation.messages

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import conectate.com.data.model.Message
import conectate.com.presentation.components.mediaGridClusterAt
import kotlinx.coroutines.flow.distinctUntilChanged

/** Read receipts follow the viewport without invalidating the conversation's composition. */
@Composable
internal fun ObserveVisibleConversationMessages(
    messages: List<Message>,
    listState: LazyListState,
    groupMedia: Boolean,
    onVisibleIdsChanged: (Set<String>) -> Unit,
) {
    val notify by rememberUpdatedState(onVisibleIdsChanged)
    LaunchedEffect(messages, listState, groupMedia) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.filter { it.size > 0 }.map { it.index to it.key }
        }.distinctUntilChanged().collect { visible ->
            val ids = visible.flatMap { (index, key) ->
                // Layout may still describe the previous message list during an update.
                val message = messages.getOrNull(index)?.takeIf { it.id == key }
                when {
                    message == null -> emptyList()
                    !groupMedia -> listOf(message.id)
                    else -> mediaGridClusterAt(messages, index)?.map(Message::id).orEmpty()
                }
            }.toSet()
            notify(ids)
        }
    }
}
