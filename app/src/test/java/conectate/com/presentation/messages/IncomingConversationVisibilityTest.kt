package conectate.com.presentation.messages

import conectate.com.data.model.Chat
import conectate.com.data.model.ChatWithLastMessage
import org.junit.Assert.*
import org.junit.Test

class IncomingConversationVisibilityTest {
    @Test fun aNewDeliveryReappearsWithoutLeavingAndReopeningTheInbox() {
        val hidden = mapOf("chat" to "2026-11-12T12:00:01Z")
        val old = ChatWithLastMessage(chat = Chat(id = "chat", last_message = "Ejemplo", last_message_at = "2026-11-12T12:00:00Z"))
        assertTrue(normalizedVisibleConversations(listOf(old), hiddenSince = hidden).isEmpty())
        val fresh = old.copy(chat = old.chat.copy(last_message_at = "2026-11-12T12:00:02Z"))
        assertEquals("chat", normalizedVisibleConversations(listOf(fresh), hiddenSince = hidden).single().chat.id)
        assertTrue(normalizedVisibleConversations(listOf(old), hiddenSince = hidden).isEmpty())
    }
}
