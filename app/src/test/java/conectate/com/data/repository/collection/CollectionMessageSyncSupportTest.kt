package conectate.com.data.repository.collection

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionMessageSyncSupportTest {
    @Test fun `attachment and poll previews name the content instead of generic multimedia`() {
        assertEquals("🧩 Sticker", CollectionMessageSyncSupport.buildMetadataPreview("sticker", ""))
        assertEquals("📊 Encuesta", CollectionMessageSyncSupport.buildMetadataPreview("poll", "¿Cuándo?"))
        assertEquals("📷 Foto", CollectionMessageSyncSupport.buildMetadataPreview("image", "Comentario"))
        assertEquals("📎 Archivo", CollectionMessageSyncSupport.buildMetadataPreview("document", "plan.pdf"))
        assertEquals("Hola", CollectionMessageSyncSupport.buildMetadataPreview("text", "Hola"))
    }
    @Test
    fun `fresh receipt replaces stale in-memory status without duplicating messages`() {
        val merged = CollectionMessageSyncSupport.mergeActiveMessages(
            current = listOf("1" to "sent", "2" to "sent"),
            incoming = listOf("1" to "read"),
            idSelector = { it.first }, createdAtSelector = { it.first }
        )
        assertEquals(listOf("1" to "read", "2" to "sent"), merged)
    }

    @Test
    fun `resolveLocallyReadableIds falls back to requested ids when remote confirmation is empty`() {
        val result = CollectionMessageSyncSupport.resolveLocallyReadableIds(
            requestedIds = listOf(" message-a ", "message-b", "message-a"),
            remotelyReadIds = emptySet()
        )

        assertEquals(listOf("message-a", "message-b"), result)
    }

    @Test
    fun `resolveLocallyReadableIds uses only confirmed ids when remote confirmation exists`() {
        val result = CollectionMessageSyncSupport.resolveLocallyReadableIds(
            requestedIds = listOf("message-a", "message-b", "message-c"),
            remotelyReadIds = setOf("message-b", "message-c")
        )

        assertEquals(listOf("message-b", "message-c"), result)
    }

    @Test
    fun `opening a conversation does not hide unseen messages`() {
        assertEquals(
            14,
            CollectionMessageSyncSupport.resolveDisplayedUnreadCount(
                isConversationOpen = true,
                exactUnread = 14
            )
        )
        assertEquals(
            3,
            CollectionMessageSyncSupport.resolveDisplayedUnreadCount(
                isConversationOpen = false,
                exactUnread = 3
            )
        )
    }
}
