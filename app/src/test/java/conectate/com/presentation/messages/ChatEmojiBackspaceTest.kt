package conectate.com.presentation.messages

import conectate.com.test.UnitTestApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34], application = UnitTestApplication::class)
class ChatEmojiBackspaceTest {
    @Test fun deletesOneCompleteEmojiWithoutLeavingJoinersModifiersOrHalfFlags() {
        listOf("😀", "👩🏽‍💻", "👨‍👩‍👧‍👦", "🇵🇪", "1️⃣", "❤️", "🫱🏽‍🫲🏻").forEach { emoji ->
            assertEquals("hola ", removeLastChatGrapheme("hola $emoji"))
        }
    }

    @Test fun preservesPreviousEmojiAndSupportsEmptyPlainAndCombiningText() {
        assertEquals("😀", removeLastChatGrapheme("😀👩🏽‍💻"))
        assertEquals("", removeLastChatGrapheme(""))
        assertEquals("hol", removeLastChatGrapheme("hola"))
        assertEquals("", removeLastChatGrapheme("a\u0301"))
        assertEquals("hola", removeLastChatGrapheme("hola\n"))
    }
}
