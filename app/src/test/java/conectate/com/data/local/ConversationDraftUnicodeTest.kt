package conectate.com.data.local

import org.junit.Assert.*
import org.junit.Test

class ConversationDraftUnicodeTest {
    @Test fun `all valid combined emoji survive intact`() {
        val text = "Hola 😀 👍🏽 👩🏽‍💻 👨‍👩‍👧‍👦 🇵🇪 ❤️ 1️⃣"
        assertSame(text, safeConversationDraft(text))
    }

    @Test fun `length cap never saves half an emoji surrogate pair`() {
        val text = "a".repeat(15_999) + "😀"
        assertEquals("a".repeat(15_999), safeConversationDraft(text))
        assertEquals("😀", safeConversationDraft("😀x", 2))
        assertEquals("", safeConversationDraft("😀x", 1))
    }

    @Test fun `legacy orphan surrogate units are repaired independently of valid emoji`() {
        assertEquals("😀\uFFFD hola \uFFFD👍🏽", safeConversationDraft("😀\uD83D hola \uDC00👍🏽"))
    }

    @Test fun `caret is bounded and cannot sit between surrogate units`() {
        assertEquals(2, safeConversationCaret("😀 hola", 1))
        assertEquals(0, safeConversationCaret("😀", -5))
        assertEquals(2, safeConversationCaret("😀", 200))
        assertEquals(3, safeConversationCaret("a😀b", 2))
    }
}
