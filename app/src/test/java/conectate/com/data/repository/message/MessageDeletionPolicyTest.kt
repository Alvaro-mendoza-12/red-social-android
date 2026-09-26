package conectate.com.data.repository.message

import conectate.com.data.local.entity.MessageDeletionEntity
import conectate.com.data.model.Message
import org.junit.Assert.*
import org.junit.Test

class MessageDeletionPolicyTest {
    private val source = Message(id = "m", chat_id = "chat", sender_id = "a", content = "private", media_url = "https://media")
    private fun intent(all: Boolean = true, actor: String = "a", state: String = "pending") =
        MessageDeletionEntity("m", actor, all, "chat", "revision", 0, state)

    @Test fun `all tombstone removes payload from presentation but not original stored message`() {
        val shown = applyMessageDeletions(listOf(source), listOf(intent()), "b").single()
        assertTrue(shown.deleted_for_all)
        assertEquals("", shown.content)
        assertNull(shown.media_url)
        assertEquals("private", source.content)
    }

    @Test fun `quoted deleted message cannot expose old text or media`() {
        val reply = Message(id = "reply", chat_id = "chat", sender_id = "b", content = "reply", reply_to = source)
        val shown = applyMessageDeletions(listOf(reply), listOf(intent()), "b").single()
        assertTrue(shown.reply_to!!.deleted_for_all)
        assertNull(shown.reply_to!!.media_url)
        assertEquals("", shown.reply_to!!.content)
        assertEquals("reply", shown.content)
    }

    @Test fun `delete for me hides quote only for its actor`() {
        val reply = Message(id = "reply", chat_id = "chat", sender_id = "b", reply_to = source)
        val intents = listOf(intent(all = false, actor = "b"))
        assertTrue(applyMessageDeletions(listOf(reply), intents, "b").single().reply_to!!.deleted_for_all)
        assertFalse(applyMessageDeletions(listOf(reply), intents, "a").single().reply_to!!.deleted_for_all)
    }

    @Test fun `rejected forged and other chat intents cannot hide a message`() {
        val rejected = intent(state = "failed")
        val forged = intent(actor = "b")
        val otherChat = intent().copy(chat_id = "other")
        assertEquals(source, applyMessageDeletions(listOf(source), listOf(rejected, forged, otherChat), "b").single())
    }
}
