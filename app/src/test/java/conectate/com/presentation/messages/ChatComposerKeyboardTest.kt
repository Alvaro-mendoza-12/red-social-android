package conectate.com.presentation.messages

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import conectate.com.test.UnitTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class ChatComposerKeyboardTest {
    private fun event(action: Int, key: Int = AndroidKeyEvent.KEYCODE_ENTER, repeat: Int = 0, meta: Int = 0) =
        KeyEvent(AndroidKeyEvent(0, 0, action, key, repeat, meta))

    @Test fun `disabled Enter setting leaves both events to the multiline field`() {
        var sent = 0
        for (action in listOf(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.ACTION_UP)) {
            assertFalse(handleChatComposerKey(event(action), false, false) { sent++ })
        }
        assertEquals(0, sent)
    }

    @Test fun `one Enter press sends once and consumes its release`() {
        var sent = 0
        assertTrue(handleChatComposerKey(event(AndroidKeyEvent.ACTION_DOWN), true, false) { sent++ })
        assertTrue(handleChatComposerKey(event(AndroidKeyEvent.ACTION_UP), true, false) { sent++ })
        assertEquals(1, sent)
    }

    @Test fun `held Enter consumes repeats without dispatching more messages`() {
        var sent = 0
        assertTrue(handleChatComposerKey(event(AndroidKeyEvent.ACTION_DOWN), true, false) { sent++ })
        for (repeat in 1..10) {
            assertTrue(handleChatComposerKey(event(AndroidKeyEvent.ACTION_DOWN, repeat = repeat), true, false) { sent++ })
        }
        assertEquals(1, sent)
    }

    @Test fun `a repeat arriving after focus changes never sends the new conversation draft`() {
        var sent = 0
        assertTrue(handleChatComposerKey(event(AndroidKeyEvent.ACTION_DOWN, repeat = 3), true, false) { sent++ })
        assertEquals(0, sent)
    }

    @Test fun `Shift Enter and system shortcuts retain their normal behavior`() {
        var sent = 0
        for (meta in listOf(AndroidKeyEvent.META_SHIFT_ON, AndroidKeyEvent.META_CTRL_ON,
            AndroidKeyEvent.META_ALT_ON, AndroidKeyEvent.META_META_ON)) {
            assertFalse(handleChatComposerKey(event(AndroidKeyEvent.ACTION_DOWN, meta = meta), true, false) { sent++ })
        }
        assertEquals(0, sent)
    }

    @Test fun `Enter confirms an active text composition without sending it`() {
        var sent = 0
        assertFalse(handleChatComposerKey(event(AndroidKeyEvent.ACTION_DOWN), true, true) { sent++ })
        assertEquals(0, sent)
    }

    @Test fun `numeric keypad Enter invokes the same submit action`() {
        var sent = 0
        assertTrue(handleChatComposerKey(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER), true, false) { sent++ })
        assertEquals(1, sent)
    }

    @Test fun `ordinary letters are not swallowed by the send shortcut`() {
        var sent = 0
        assertFalse(handleChatComposerKey(event(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_A), true, false) { sent++ })
        assertEquals(0, sent)
    }

    @Test fun `an empty edit dispatches edit without falling through to the microphone`() {
        var edits = 0
        var sends = 0
        assertTrue(submitChatComposerDraft("", true, onEdit = { edits++ }, onSend = { sends++ }))
        assertEquals(1, edits)
        assertEquals(0, sends)
    }

    @Test fun `ordinary empty input remains available for the microphone button`() {
        var actions = 0
        assertFalse(submitChatComposerDraft(" \n", false, onEdit = { actions++ }, onSend = { actions++ }))
        assertEquals(0, actions)
    }

    @Test fun `blocked conversation never dispatches send or edit`() {
        var actions = 0
        for (editing in listOf(false, true)) {
            assertFalse(submitChatComposerDraft("mensaje", editing, canSend = false,
                onEdit = { actions++ }, onSend = { actions++ }))
        }
        assertEquals(0, actions)
    }

    @Test fun `draft text dispatches send rather than edit`() {
        var edits = 0
        var sends = 0
        assertTrue(submitChatComposerDraft("mensaje", false, onEdit = { edits++ }, onSend = { sends++ }))
        assertEquals(0, edits)
        assertEquals(1, sends)
    }

    @Test fun `an edited message dispatches only its edit action`() {
        var edits = 0
        var sends = 0
        assertTrue(submitChatComposerDraft("cambio", true, onEdit = { edits++ }, onSend = { sends++ }))
        assertEquals(1, edits)
        assertEquals(0, sends)
    }

    @Test fun `hardware action reads the current text field contents at dispatch time`() {
        val field = androidx.compose.foundation.text.input.TextFieldState("antes")
        field.edit { replace(0, length, "texto actual") }
        var submitted: String? = null
        handleChatComposerKey(event(AndroidKeyEvent.ACTION_DOWN), true, false) {
            submitChatComposerDraft(field.text.toString(), false,
                onEdit = { fail("Unexpected edit") }, onSend = { submitted = field.text.toString() })
        }
        assertEquals("texto actual", submitted)
    }
}
