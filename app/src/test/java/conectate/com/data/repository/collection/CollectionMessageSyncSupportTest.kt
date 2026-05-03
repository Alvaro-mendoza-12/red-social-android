package conectate.com.data.repository.collection

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionMessageSyncSupportTest {

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
    fun `resolveDisplayedUnreadCount hides badge while conversation is open`() {
        assertEquals(
            0,
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
