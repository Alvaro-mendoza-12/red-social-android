package conectate.com.core.messaging

import conectate.com.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingMessageSyncPolicyTest {

    @Test
    fun `acknowledgement without a body preserves uploaded remote media`() {
        val queued = Message(
            id = "message-1",
            chat_id = "chat-1",
            sender_id = "sender-1",
            content_type = "audio",
            media_url = "https://cdn.example.test/chat-media/message-1.m4a",
            thumbnail_url = "https://cdn.example.test/thumbnails/message-1.webp",
            status = "sending"
        )

        val acknowledged = PendingMessageSyncPolicy.acknowledgedMessage(
            serverMessage = null,
            queuedMessage = queued,
            chatId = " CHAT-1 "
        )

        assertEquals("chat-1", acknowledged.chat_id)
        assertEquals("sent", acknowledged.status)
        assertEquals(queued.media_url, acknowledged.media_url)
        assertEquals(queued.thumbnail_url, acknowledged.thumbnail_url)
    }

    @Test
    fun `backend body takes precedence when it is available`() {
        val queued = Message(
            id = "message-2",
            chat_id = "chat-2",
            sender_id = "sender-2",
            content = "local",
            media_url = "file:///private/pending-message-media/message-2.m4a",
            status = "sending"
        )
        val server = queued.copy(
            content = "server",
            media_url = "https://cdn.example.test/chat-media/message-2.m4a",
            status = "delivered"
        )

        val acknowledged = PendingMessageSyncPolicy.acknowledgedMessage(
            serverMessage = server,
            queuedMessage = queued,
            chatId = "chat-2"
        )

        assertEquals("server", acknowledged.content)
        assertEquals(server.media_url, acknowledged.media_url)
        assertEquals("delivered", acknowledged.status)
    }

    @Test
    fun `read acknowledgement remains read even when status field is stale`() {
        val queued = Message(
            id = "message-3",
            chat_id = "chat-3",
            sender_id = "sender-3",
            content = "local",
            status = "sending"
        )
        val server = queued.copy(
            status = "sent",
            delivered_at = "2026-09-10T12:00:00Z",
            read_at = "2026-09-10T12:01:00Z"
        )

        val acknowledged = PendingMessageSyncPolicy.acknowledgedMessage(
            serverMessage = server,
            queuedMessage = queued,
            chatId = "chat-3"
        )

        assertEquals("read", acknowledged.status)
    }
}
