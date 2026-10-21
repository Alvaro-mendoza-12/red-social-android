package conectate.com.presentation.proximity

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.localfallback.AnonymousChatMessage
import conectate.com.data.localfallback.AnonymousChatSession
import conectate.com.data.localfallback.AnonymousPeer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnonymousConversationLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val session = AnonymousChatSession(
        id = "local-layout", status = "active", ownRevealLevel = 3, peerRevealLevel = 3,
        messageCount = 12,
        peer = AnonymousPeer("alias", "Persona con un nombre largo para comprobar el espacio",
            interests = listOf("programación", "fotografía", "música"), userId = "local-peer"),
    )
    private val messages = (0..14).map { index -> AnonymousChatMessage(
        id = "message-$index", clientMessageId = "client-$index", sender = if (index % 2 == 0) "me" else "peer",
        content = if (index == 14) "Último mensaje" else "Mensaje $index", createdAt = "2026-10-20T22:00:00Z",
    ) }

    @Test fun compactViewportKeepsMessagesAndInputVisible() {
        compose.setContent { CompactViewport { Conversation() } }
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Último mensaje").assertIsDisplayed()
        saveScreen("compact")
        compose.onNodeWithContentDescription("Adjuntar").performClick()
        listOf("Audio", "Sticker", "Foto").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun largeTextDoesNotCollapseTheComposerOrSendTwice() {
        var sent = ""
        var sends = 0
        compose.setContent { CompactViewport(fontScale = 2f) { Conversation { sent = it; sends++ } } }
        compose.onNode(hasSetTextAction()).assertHeightIsAtLeast(56.dp).performTextInput("Hola")
        compose.onNodeWithContentDescription("Enviar").performClick()
        assertEquals("Hola", sent)
        assertEquals(1, sends)
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithContentDescription("Enviar").assertIsNotEnabled()
        saveScreen("large-text")
    }

    @Test fun profileAndContinueRemainAvailableWithoutOccupyingTheTimeline() {
        compose.setContent { CompactViewport { Conversation() } }
        compose.onNodeWithText("Continuar en chats").assertDoesNotExist()
        compose.onNodeWithContentDescription("Detalles de la conversación").performClick()
        compose.onNodeWithText("Confianza: 100% · Identidad: 3/3").assertIsDisplayed()
        compose.onNodeWithText("Ver perfil compartido").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Continuar en chats").performScrollTo().assertIsDisplayed()
    }

    @Test fun realKeyboardResizesTheConversationWithoutFlatteningTheInput() {
        val keyboardBottom = AtomicInteger(0)
        compose.setContent { MaterialTheme {
            val bottom = WindowInsets.ime.getBottom(LocalDensity.current)
            SideEffect { keyboardBottom.set(bottom) }
            Conversation()
        } }
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitUntil(20_000) { keyboardBottom.get() > 0 }
        compose.onNode(hasSetTextAction()).assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Último mensaje").assertIsDisplayed()
        saveScreen("keyboard-open")
    }

    @Composable private fun CompactViewport(fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MaterialTheme {
                // The constrained height represents the viewport AFTER the IME
                // has resized it; do not subtract the real keyboard a second time.
                Box(Modifier.requiredSize(320.dp, 340.dp).consumeWindowInsets(WindowInsets.ime)) { content() }
            }
        }
    }

    @Composable private fun Conversation(onSend: (String) -> Unit = {}) {
        AnonymousConversation(session, messages, onSend, { _, _ -> }, { _, _ -> }, {}, {}, "local-peer", {}, {})
    }

    private fun saveScreen(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
        File(folder, "bottle-layout-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
