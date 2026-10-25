package conectate.com.presentation.messages

import conectate.com.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VisibleMessageLookupTest {
    @Test fun `resolves only visible messages from the currently indexed Room snapshot`() {
        val first = Message(id = "first", sender_id = "other")
        val second = Message(id = "second", sender_id = "me")
        val third = Message(id = "third", sender_id = "other")
        val snapshot = listOf(first, second, third)
        val lookup = VisibleMessageLookup<Message>(Message::id)

        val visible = lookup.find(snapshot, setOf("third", "missing"))

        assertEquals(1, visible.size)
        assertSame(third, visible.single())
        assertSame(second, lookup.find(snapshot, setOf("second")).single())
    }

    @Test fun `replaces its index when Room publishes a new snapshot`() {
        val lookup = VisibleMessageLookup<Message>(Message::id)
        val old = listOf(Message(id = "same", content = "old"))
        val updated = listOf(Message(id = "same", content = "updated"))

        assertEquals("old", lookup.find(old, setOf("same")).single().content)
        assertEquals("updated", lookup.find(updated, setOf("same")).single().content)
    }
}
