package conectate.com.data.local.entity

import conectate.com.data.model.Chat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatEntityTest {

    @Test
    fun `group image survives Room entity round trip`() {
        val entity = ChatEntity.fromDomain(
            chat = Chat(id = "group", is_group = true, group_name = "Familia"),
            displayName = "Familia",
            displayImage = "https://cdn.test/groups/family.jpg",
            unreadCount = 2,
            lastMsg = "Hola",
            lastTime = "2026-09-04T10:00:00Z",
        )

        val restored = entity.toDomainWithDetails()

        assertEquals("https://cdn.test/groups/family.jpg", restored.chat.group_image)
        assertEquals("https://cdn.test/groups/family.jpg", restored.otherUserImage)
    }

    @Test
    fun `direct chat image does not become a group image`() {
        val entity = ChatEntity.fromDomain(
            chat = Chat(id = "direct", is_group = false),
            displayName = "Persona",
            displayImage = "https://cdn.test/avatars/person.jpg",
            unreadCount = 0,
            lastMsg = null,
            lastTime = "2026-09-04T10:00:00Z",
            otherUserId = "person",
        )

        val restored = entity.toDomainWithDetails()

        assertNull(restored.chat.group_image)
        assertEquals("https://cdn.test/avatars/person.jpg", restored.otherUserImage)
    }
}
