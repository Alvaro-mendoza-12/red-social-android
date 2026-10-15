package conectate.com.data.model

import org.junit.Assert.*
import org.junit.Test

class MentionDraftTest {
    @Test fun queryUsesCaretAndRejectsEmailsAndWhitespace() {
        assertEquals("se", activeMentionAt("Hola @se y Ana", 8)?.query)
        assertNull(activeMentionAt("a@se.com", 5))
        assertNull(activeMentionAt("@se bas", 7))
        assertEquals("", activeMentionAt("@", 1)?.query)
        assertNull(activeMentionAt("@" + "a".repeat(25), 26))
        assertNull(activeMentionAt("@se", -1))
    }
    @Test fun insertionKeepsTheRestOfTheDraftAndMovesCaret() {
        val text = "Hola @se y Ana"
        val (updated, cursor) = insertMention(text, activeMentionAt(text, 8)!!, "sebas")
        assertEquals("Hola @sebas  y Ana", updated)
        assertEquals(12, cursor)
    }
    @Test fun editingInsideAnExistingHandleReplacesItsWholeToken() {
        val text = "Hola @sebas hoy"
        assertEquals("Hola @sebastian  hoy", insertMention(text, activeMentionAt(text, 8)!!, "sebastian").first)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidHandleCannotBeInserted() {
        insertMention("@se", activeMentionAt("@se", 3)!!, "a b")
    }
}
