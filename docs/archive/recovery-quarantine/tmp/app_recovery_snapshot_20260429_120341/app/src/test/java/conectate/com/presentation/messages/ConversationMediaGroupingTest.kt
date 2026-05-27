package conectate.com.presentation.messages

import conectate.com.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMediaGroupingTest {

    @Test
    fun consecutivePlainImages_sameSender_areClustered() {
        val renderItems = buildConversationRenderableItems(
            listOf(
                mediaMessage("1", "user-a", "2026-04-19T10:00:00Z"),
                mediaMessage("2", "user-a", "2026-04-19T10:01:00Z"),
                mediaMessage("3", "user-a", "2026-04-19T10:02:00Z")
            )
        ) { it }

        assertEquals(1, renderItems.size)
        val cluster = renderItems.first() as ConversationRenderableItem.MediaCluster
        assertEquals(listOf("1", "2", "3"), cluster.items.map { it.id })
    }

    @Test
    fun captionedImage_breaksCluster() {
        val renderItems = buildConversationRenderableItems(
            listOf(
                mediaMessage("1", "user-a", "2026-04-19T10:00:00Z"),
                mediaMessage("2", "user-a", "2026-04-19T10:01:00Z", content = "mira esto"),
                mediaMessage("3", "user-a", "2026-04-19T10:02:00Z")
            )
        ) { it }

        assertEquals(3, renderItems.size)
        assertTrue(renderItems.all { it is ConversationRenderableItem.Single })
    }

    @Test
    fun differentSender_startsNewCluster() {
        val renderItems = buildConversationRenderableItems(
            listOf(
                mediaMessage("1", "user-a", "2026-04-19T10:00:00Z"),
                mediaMessage("2", "user-a", "2026-04-19T10:01:00Z"),
                mediaMessage("3", "user-b", "2026-04-19T10:02:00Z"),
                mediaMessage("4", "user-b", "2026-04-19T10:03:00Z")
            )
        ) { it }

        assertEquals(2, renderItems.size)
        val first = renderItems[0] as ConversationRenderableItem.MediaCluster
        val second = renderItems[1] as ConversationRenderableItem.MediaCluster
        assertEquals(listOf("1", "2"), first.items.map { it.id })
        assertEquals(listOf("3", "4"), second.items.map { it.id })
    }

    private fun mediaMessage(
        id: String,
        senderId: String,
        createdAt: String,
        content: String = ""
    ) = Message(
        id = id,
        sender_id = senderId,
        content = content,
        content_type = "image",
        media_url = "https://cdn.example.com/$id.jpg",
        created_at = createdAt
    )
}
