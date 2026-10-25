package conectate.com.presentation.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ChatComposerIsolationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun typingDoesNotRecomposeTheConversationOrTimeline() {
        val text = MutableStateFlow("")
        val conversationCompositions = AtomicInteger()
        val timelineCompositions = AtomicInteger()
        val inputCompositions = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                SideEffect { conversationCompositions.incrementAndGet() }
                Column {
                    Column(Modifier.height(180.dp)) {
                        SideEffect { timelineCompositions.incrementAndGet() }
                        LazyColumn { items(500) { Text("Mensaje $it") } }
                    }
                    ChatComposerDraft(text) { draft ->
                        SideEffect { inputCompositions.incrementAndGet() }
                        var fieldValue by conectate.com.presentation.components.rememberChatSpellingDraft(draft)
                        val state = rememberChatComposerFieldState(fieldValue) { fieldValue = it; text.value = it.text }
                        val spelling = conectate.com.presentation.components.rememberChatSpellCheckState(draft)
                        TextField(
                            state = state,
                            inputTransformation = ChatUnicodeInputTransformation,
                            outputTransformation = rememberChatComposerOutputTransformation(spelling.issues, false),
                            modifier = Modifier.testTag("input"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val initialConversation = conversationCompositions.get()
        val initialTimeline = timelineCompositions.get()
        val initialInput = inputCompositions.get()
        for (letter in "Hola desde el teclado") {
            compose.onNodeWithTag("input").performTextInput(letter.toString())
            compose.waitForIdle()
        }
        compose.onNodeWithTag("input").assertTextContains("Hola desde el teclado")
        assertTrue(inputCompositions.get() > initialInput)
        assertEquals(initialConversation, conversationCompositions.get())
        assertEquals(initialTimeline, timelineCompositions.get())
    }

    @Test fun mentionDraftAndCursorUpdatesStayInsideTheInputBoundary() {
        val text = MutableStateFlow("")
        val conversationCompositions = AtomicInteger()
        val timelineCompositions = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                SideEffect { conversationCompositions.incrementAndGet() }
                Column {
                    SideEffect { timelineCompositions.incrementAndGet() }
                    Text("Historial de la comunidad")
                    ChatComposerDraft(text) { draft ->
                        var selection by remember { mutableStateOf(TextFieldValue(draft)) }
                        val mention = conectate.com.data.model.activeMentionAt(selection.text, selection.selection.end)
                        Text(mention?.query.orEmpty(), Modifier.testTag("mention"))
                        TextField(
                            value = selection,
                            onValueChange = { selection = it.copy(selection = TextRange(it.text.length)); text.value = it.text },
                            modifier = Modifier.testTag("input"),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        val initialConversation = conversationCompositions.get()
        val initialTimeline = timelineCompositions.get()
        compose.onNodeWithTag("input").performTextInput("@maria")
        compose.waitForIdle()
        compose.onNodeWithTag("mention").assertTextContains("maria")
        assertEquals(initialConversation, conversationCompositions.get())
        assertEquals(initialTimeline, timelineCompositions.get())
    }
}
