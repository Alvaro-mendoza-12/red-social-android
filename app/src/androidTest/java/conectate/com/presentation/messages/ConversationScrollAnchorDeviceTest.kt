package conectate.com.presentation.messages

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.Message
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

@RunWith(AndroidJUnit4::class)
class ConversationScrollAnchorDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingLargeConversationStartsAtNewestMessage() {
        val messages = (1..250).map { index ->
            Message(id = "message-$index", sender_id = "other")
        }
        lateinit var listState: LazyListState

        compose.setContent {
            listState = rememberConversationListState()
            ConversationScrollAnchor(
                conversationKey = "direct:me:other",
                messages = messages,
                currentUserId = "me",
                focusMessageId = null,
                listState = listState,
                messageId = Message::id,
                senderId = Message::sender_id,
            )
            LazyColumn(state = listState) {
                items(messages, key = Message::id, contentType = { "message" }) { message ->
                    Text(message.id, Modifier.height(48.dp))
                }
            }
        }

        compose.waitForIdle()
        compose.runOnIdle {
            val visible = listState.layoutInfo.visibleItemsInfo.map { it.key }
            assertTrue("Chat opened at the history start: $visible", visible.contains("message-250"))
            assertTrue("Chat still includes the oldest message in its first viewport: $visible", !visible.contains("message-1"))
        }
    }

    @Test fun realtimeAppendDoesNotCancelAnActiveNativeScroll() {
        val messages = mutableStateOf((1..250).map { Message(id = "message-$it", sender_id = "other") })
        lateinit var listState: LazyListState
        lateinit var scope: CoroutineScope
        var scroll: Job? = null
        compose.setContent {
            scope = rememberCoroutineScope()
            listState = rememberConversationListState()
            ConversationScrollAnchor(
                conversationKey = "append-during-fling",
                messages = messages.value,
                currentUserId = "me",
                focusMessageId = null,
                listState = listState,
                messageId = Message::id,
                senderId = Message::sender_id,
            )
            LazyColumn(state = listState, modifier = Modifier.height(240.dp)) {
                items(messages.value, key = Message::id, contentType = { "message" }) {
                    Text(it.id, Modifier.height(48.dp))
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            // LazyList's native fling also owns the Default-priority scroll
            // mutation. A competing scrollToItem would cancel this owner.
            scroll = scope.launch { listState.scroll { awaitCancellation() } }
        }
        compose.waitUntil(5_000) { listState.isScrollInProgress }
        try {
            compose.runOnIdle {
                messages.value = messages.value + Message(id = "message-new", sender_id = "me")
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue("A realtime update interrupted the native scroll", scroll?.isActive == true)
            }
        } finally {
            compose.runOnIdle { scroll?.cancel() }
        }
    }
}
