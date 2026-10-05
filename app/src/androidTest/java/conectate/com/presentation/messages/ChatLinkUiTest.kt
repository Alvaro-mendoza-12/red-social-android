package conectate.com.presentation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.Message
import conectate.com.presentation.components.MessageBubble
import conectate.com.presentation.theme.resolveChatAccent
import conectate.com.presentation.theme.textContrastRatio
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
class ChatLinkUiTest {
    @get:Rule val compose = createComposeRule()

    private fun render(text: String, own: Boolean, external: (String) -> Unit, internal: (String) -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { external(uri) }
            }) {
                MaterialTheme {
                    Box(Modifier.width(300.dp)) {
                        MessageBubble(Message(id="qa-link", content=text, sender_id="qa", content_type="text"), own,
                            onUrlClick=internal)
                    }
                }
            }
        }
    }

    private fun clickAt(text: String, offset: Int): TextLayoutResult {
        val node = compose.onNodeWithText(text, useUnmergedTree=true)
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        node.performTouchInput { click(layout.getBoundingBox(offset).center) }
        compose.waitForIdle()
        return layout
    }

    @Test fun sentBareLinkIsBlueAndOpensItsDestinationWithinTheBubble() {
        var opened: String? = null
        val text="Mira www.example.org/test."
        render(text, true, { opened=it }, { fail("External link entered an internal route") })
        val layout=clickAt(text, text.indexOf("example"))
        val link=layout.layoutInput.text.getLinkAnnotations(0,text.length).single().item as LinkAnnotation.Url
        val style = requireNotNull(link.styles?.style)
        assertTrue(style.color.blue > style.color.red && style.color.blue > style.color.green)
        val background = style.background.takeUnless { it == Color.Unspecified }
            ?: resolveChatAccent("conectate_red", darkTheme = false).sentBackground
        assertTrue(textContrastRatio(style.color, background) >= 4.5f)
        compose.runOnIdle { assertEquals("https://www.example.org/test", opened) }
    }

    @Test fun receivedHttpsLinkOpensAndKeepsOtherTextReadable() {
        var opened: String? = null
        val text="Consulta https://example.org/report?q=1 y dime."
        render(text, false, { opened=it }, { fail("External link entered an internal route") })
        clickAt(text,text.indexOf("example"))
        compose.runOnIdle { assertEquals("https://example.org/report?q=1", opened) }
        compose.onNodeWithText(text, useUnmergedTree=true).assertIsDisplayed()
    }

    @Test fun ownPostNavigatesInternallyWithoutOpeningTheBrowser() {
        var opened: String? = null
        val text="https://conectate.com/post/post_1234"
        render(text, true, { fail("Owned post opened browser") }, { opened=it })
        clickAt(text,text.indexOf("post_"))
        compose.runOnIdle { assertEquals(text, opened) }
    }

    @Test fun externalLinkUsesTheAndroidViewIntentWithItsExactDestination() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val opened = AtomicReference<android.content.Intent?>()
        val destination = "https://example.org/report?q=1"
        val text = "Consulta $destination y dime."
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.content.Intent.ACTION_VIEW) return null
                opened.set(android.content.Intent(intent))
                // Verify the OS handoff without depending on a remote test website.
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.setContent {
                MaterialTheme {
                    Box(Modifier.width(300.dp)) {
                        MessageBubble(Message(id = "qa-native-link", content = text, sender_id = "qa", content_type = "text"), false,
                            onUrlClick = { fail("External URL entered an internal route") })
                    }
                }
            }
            clickAt(text, text.indexOf("example"))
            assertEquals(android.content.Intent.ACTION_VIEW, opened.get()?.action)
            assertEquals(destination, opened.get()?.dataString)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
