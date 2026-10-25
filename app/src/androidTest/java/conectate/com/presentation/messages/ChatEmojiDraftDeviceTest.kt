package conectate.com.presentation.messages

import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.local.ConversationDraftStore
import conectate.com.presentation.components.rememberChatSpellCheckState
import conectate.com.presentation.components.rememberChatSpellingDraft
import conectate.com.presentation.messages.stickers.ChatEmojiGrid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatEmojiDraftDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ConversationDraftStore(InstrumentationRegistry.getInstrumentation().targetContext)
    private val owner = "emoji-qa"
    private val room = "emoji-draft-${java.util.UUID.randomUUID()}"
    private var draft by mutableStateOf("")
    private var visible by mutableStateOf(true)
    private lateinit var nativeField: androidx.compose.foundation.text.input.TextFieldState

    @After fun clearOwnDraft() {
        store.write(owner, "chat", room, "")
        (InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager).clearPrimaryClip()
    }

    private fun showComposer(initial: String = "") {
        draft = initial
        compose.setContent {
            MaterialTheme {
                if (visible) {
                    val spelling = rememberChatSpellCheckState(draft)
                    var external by rememberChatSpellingDraft(draft)
                    val state = rememberChatComposerFieldState(external) {
                        external = it
                        draft = it.text
                        store.write(owner, "chat", room, it.text)
                    }
                    nativeField = state
                    TextField(
                        state = state,
                        inputTransformation = ChatUnicodeInputTransformation,
                        outputTransformation = rememberChatComposerOutputTransformation(spelling.issues, true),
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                        modifier = Modifier.testTag("emoji_input"),
                    )
                }
            }
        }
    }

    @Test fun keyboardCommitSupportsSingleCombinedAndFlagEmoji() {
        showComposer()
        val input = compose.onNodeWithTag("emoji_input")
        var expected = ""
        for (text in listOf("Hola ", "😀", "👍🏽", "👩🏽‍💻", "👨‍👩‍👧‍👦", "🇵🇪", "❤️", " @maria")) {
            input.performTextInput(text)
            expected += text
            compose.waitForIdle()
            input.assertTextContains(expected)
            compose.runOnIdle { assertEquals(expected, draft) }
        }
    }

    @Test fun savedEmojiDraftCanBeReopenedAndEdited() {
        val original = "😀 👍🏽 👩🏽‍💻 👨‍👩‍👧‍👦 🇵🇪 ❤️"
        store.write(owner, "chat", room, original)
        showComposer(store.read(owner, "chat", room))
        compose.onNodeWithTag("emoji_input").assertTextContains(original)
        compose.onNodeWithTag("emoji_input").performTextInput(" listo")
        compose.waitForIdle()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        compose.runOnIdle {
            draft = store.read(owner, "chat", room)
            visible = true
        }
        compose.onNodeWithTag("emoji_input").assertTextContains(original + " listo")
        compose.onNodeWithTag("emoji_input").performTextReplacement("Otro mensaje 😀")
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Otro mensaje 😀", store.read(owner, "chat", room)) }
    }

    @Test fun brokenLegacyDraftIsRecoveredWithoutRemovingValidEmoji() {
        val preferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("conversation-drafts-v1", android.content.Context.MODE_PRIVATE)
        preferences.edit().putString(ConversationDraftStore.key(owner, "chat", room), "😀\uD83D texto \uDC00👍🏽").commit()
        showComposer(store.read(owner, "chat", room))
        compose.onNodeWithTag("emoji_input").assertTextContains("😀\uFFFD texto \uFFFD👍🏽")
        compose.onNodeWithTag("emoji_input").performTextInput(" listo")
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("😀\uFFFD texto \uFFFD👍🏽 listo", store.read(owner, "chat", room)) }
    }

    @Test fun androidClipboardEmojiPasteUsesTheSameUnicodeSafeInputPath() {
        showComposer()
        val expected = "👩🏽‍💻 🇵🇪 ❤️ 😀"
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val clipboard = instrumentation.targetContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("emoji-qa", expected))
        compose.onNodeWithTag("emoji_input").performClick()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_PASTE)
        compose.waitUntil(5_000) { draft == expected }
        compose.onNodeWithTag("emoji_input").assertTextContains(expected)
        assertEquals(expected, store.read(owner, "chat", room))
    }

    @Test fun inAppEmojiPickerShowsAndReturnsEmojiForTheDraft() {
        var selected by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                ChatEmojiGrid(query = "", modifier = Modifier, onEmojiSelected = { selected = it })
            }
        }
        compose.onNodeWithContentDescription("sonrisa feliz").performClick()
        compose.runOnIdle { assertEquals("😀", selected) }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test fun emojiOnlyChatMessageRendersItsContentInsteadOfOnlyItsTimestamp() {
        compose.setContent {
            MaterialTheme {
                conectate.com.presentation.components.MessageBubble(
                    message = conectate.com.data.model.Message(id = "emoji-only", content = "😀", content_type = "text"),
                    isFromCurrentUser = true,
                )
            }
        }
        compose.onNodeWithText("😀").assertExists()
        assertTrue(conectate.com.presentation.components.isStandaloneEmojiMessage("👩🏽‍💻 🇵🇪 ❤️"))
        assertTrue(!conectate.com.presentation.components.isStandaloneEmojiMessage("Hola 😀"))
    }

    /** Exercise Android's IME contract, including composing text and code point deletion. */
    private fun withInputConnection(block: (android.view.inputmethod.InputConnection) -> Unit) {
        compose.onNodeWithTag("emoji_input").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            fun editor(view: android.view.View): android.view.View? {
                if (view.onCheckIsTextEditor()) return view
                if (view is android.view.ViewGroup) {
                    for (index in 0 until view.childCount) editor(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            val input = editor(compose.activity.window.decorView)
                ?.onCreateInputConnection(android.view.inputmethod.EditorInfo())
            assertNotNull("The focused composer must expose Android's input connection", input)
            block(requireNotNull(input))
        }
        compose.waitForIdle()
    }

    @Test fun composingKeyboardCanReplaceEmojiAndDeleteByCodePoint() {
        showComposer()
        withInputConnection { input ->
            assertTrue(input.beginBatchEdit())
            input.setComposingText("Hola 👩", 1)
            input.setComposingText("Hola 👩🏽‍💻", 1)
            input.commitText("Hola 👩🏽‍💻 ", 1)
            input.commitText("😀", 1)
            input.endBatchEdit()
        }
        compose.runOnIdle { assertEquals("Hola 👩🏽‍💻 😀", draft) }
        withInputConnection { input ->
            input.deleteSurroundingTextInCodePoints(1, 0)
            input.commitText("🇵🇪", 1)
        }
        compose.runOnIdle { assertEquals("Hola 👩🏽‍💻 🇵🇪", draft) }
        assertEquals("Hola 👩🏽‍💻 🇵🇪", store.read(owner, "chat", room))
    }

    @Test fun rapidKeyboardCommitsAndSelectionReplacementKeepEveryEmoji() {
        showComposer()
        val emoji = listOf("😀", "👍🏽", "👩🏽‍💻", "👨‍👩‍👧‍👦", "🇵🇪", "❤️", "1️⃣")
        withInputConnection { input ->
            repeat(20) { emoji.forEach { input.commitText(it, 1) } }
        }
        val expected = emoji.joinToString("").repeat(20)
        compose.runOnIdle { assertEquals(expected, draft) }
        withInputConnection { input ->
            input.setSelection(0, expected.length)
            input.setComposingText("Listo 👍🏽", 1)
            input.finishComposingText()
        }
        compose.runOnIdle { assertEquals("Listo 👍🏽", draft) }
        compose.runOnIdle { visible = false }
        compose.runOnIdle { draft = store.read(owner, "chat", room); visible = true }
        compose.onNodeWithTag("emoji_input").assertTextContains("Listo 👍🏽")
    }

    @Test fun immediateSendReadsTheLastNativeEmojiCommitWithoutWaitingForTheDraftObserver() {
        showComposer()
        val expected = "Pegado 👩🏽‍💻 🇵🇪 ❤️"
        var published = ""
        withInputConnection { input ->
            input.commitText(expected, 1)
            // Same Main-thread event, before waiting for a recomposition or Flow
            // collector. This is also what the actual Send buttons now call.
            val submitted = nativeField.publishCurrentChatDraft { published = it }
            assertEquals(expected, submitted)
            assertEquals(expected, published)
        }
        compose.onNodeWithTag("emoji_input").assertTextContains(expected)
    }
}
