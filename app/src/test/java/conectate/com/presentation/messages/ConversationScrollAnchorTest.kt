package conectate.com.presentation.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationScrollAnchorTest {
    @Test fun `first layout anchors to newest message instead of history start`() {
        val messageIds = (1..500).map { "message-$it" }
        assertEquals(499, initialConversationScrollIndex(messageIds, null) { it })
    }

    @Test fun `explicit deep link takes precedence over latest message`() {
        val messageIds = listOf("old", "target", "latest")
        assertEquals(1, initialConversationScrollIndex(messageIds, "TARGET") { it })
    }

    @Test fun `missing deep link waits for the message to load`() {
        assertEquals(-1, initialConversationScrollIndex(listOf("old", "latest"), "not-loaded") { it })
    }
}
