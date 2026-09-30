package conectate.com

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.VoiceParticipant
import conectate.com.presentation.voicerooms.ParticipantCard
import conectate.com.presentation.voicerooms.ReactionPicker
import conectate.com.presentation.messages.VoiceRoomCodeCard
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VoiceRoomLayoutAuditTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mutedBadgeRemainsVisibleBeyondTheAvatarEdge() {
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize().padding(24.dp)) {
            ParticipantCard(VoiceParticipant(id = "layout-only", name = "Adolf", isMuted = true))
        } } }
        compose.onNodeWithContentDescription("Micrófono silenciado", useUnmergedTree = true).assertIsDisplayed()
        val avatar = compose.onNodeWithTag("voice_participant_avatar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val badge = compose.onNodeWithTag("voice_participant_muted_badge", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Muted badge should extend outside the avatar", badge.right > avatar.right && badge.bottom > avatar.bottom)
        screenshot("voice-muted-badge")
    }

    @Test fun reactionsOpenNearTheBottomAndCloseExplicitly() {
        val opened = mutableStateOf(true)
        var selected: String? = null
        compose.setContent { MaterialTheme {
            if (opened.value) ReactionPicker(onSelect = { selected = it; opened.value = false }, onDismiss = { opened.value = false })
        } }
        compose.waitForIdle()
        val picker = compose.onNodeWithTag("voice_reaction_picker").fetchSemanticsNode().boundsInWindow
        val height = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.heightPixels
        assertTrue("Reactions should be in the lower half", picker.top > height / 2f)
        screenshot("voice-reactions-bottom")
        compose.onNodeWithText("❤️").performClick()
        assertEquals("❤️", selected)
        compose.onNodeWithTag("voice_reaction_picker").assertDoesNotExist()
    }

    @Test fun largeTextReactionsRemainScrollableAndDismissible() {
        val opened = mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { if (opened.value) ReactionPicker({}, { opened.value = false }) }
            }
        }
        compose.onNodeWithTag("voice_reaction_picker").performScrollToNode(hasText("🎉"))
        compose.onNodeWithText("🎉").assertIsDisplayed().assertHasClickAction()
        screenshot("voice-reactions-large-text")
        compose.onNodeWithContentDescription("Cerrar reacciones").performClick()
        compose.onNodeWithTag("voice_reaction_picker").assertDoesNotExist()
    }

    @Test fun sevenDigitCodeFitsWithDoubleTextAndCanBeCopied() {
        var copied = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { Box(Modifier.fillMaxSize().padding(24.dp)) {
                    VoiceRoomCodeCard("7654321", { copied = true }, Modifier.requiredWidth(280.dp))
                } }
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText("7654321", useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertFalse(layouts.single().hasVisualOverflow)
        screenshot("voice-short-code-double-text")
        compose.onNodeWithText("7654321").performClick()
        assertTrue(copied)
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(600)
        val folder = instrumentation.targetContext.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
