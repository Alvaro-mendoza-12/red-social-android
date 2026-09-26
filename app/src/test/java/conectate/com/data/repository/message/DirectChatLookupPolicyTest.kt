package conectate.com.data.repository.message

import conectate.com.data.model.Chat
import conectate.com.data.local.entity.ChatEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectChatLookupPolicyTest {
    private fun cached(id: String, participants: List<String>, time: String = "2026-09-04T00:00:00Z") =
        ChatEntity.fromDomain(
            chat = Chat(id = id, participants = participants, is_group = false, last_message_at = time),
            displayName = "Person", displayImage = "https://example.test/avatar.webp",
            unreadCount = 0, lastMsg = null, lastTime = time, otherUserId = "person"
        )

    @Test fun `same person on two accounts selects the exact owner`() {
        val wrong = cached("wrong", listOf("old-account", "person"))
        val right = cached("right", listOf("active", "person"))
        assertEquals("right", selectCachedDirectChat(listOf(wrong, right), " ACTIVE ", "PERSON")?.id)
        assertNull(selectCachedDirectChat(listOf(wrong), "active", "person"))
    }

    @Test fun `newest exact chat wins while deleted and malformed rows are excluded`() {
        val old = cached("old", listOf("active", "person"), "2026-01-01T00:00:00Z")
        val recent = cached("recent", listOf("active", "person"))
        val deleted = recent.copy(id = "deleted", is_deleted = true, last_message_at = "2027-01-01T00:00:00Z")
        val malformed = recent.copy(id = "bad", participantsJson = "invalid")
        assertEquals("recent", selectCachedDirectChat(listOf(old, recent, deleted, malformed), "active", "person")?.id)
        assertNull(selectCachedDirectChat(listOf(recent), "active", "active"))
    }

    @Test
    fun `cached direct chat matches regardless of participant order and case`() {
        val chat = Chat(
            id = "chat",
            participants = listOf(" PERSON-B ", "person-a"),
            is_group = false,
        )

        assertTrue(chat.matchesExactDirectParticipants(listOf("PERSON-A", "person-b")))
    }

    @Test
    fun `group or chat with an extra participant cannot satisfy direct lookup`() {
        val group = Chat(participants = listOf("a", "b"), is_group = true)
        val oversized = Chat(participants = listOf("a", "b", "c"), is_group = false)

        assertFalse(group.matchesExactDirectParticipants(listOf("a", "b")))
        assertFalse(oversized.matchesExactDirectParticipants(listOf("a", "b")))
    }
}
