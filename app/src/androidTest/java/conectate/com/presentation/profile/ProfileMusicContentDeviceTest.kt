package conectate.com.presentation.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.ProfileMusicSummary
import conectate.com.data.model.withReaction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileMusicContentDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reactionToggleAndOwnerPresentationKeepTheNativePlayer() {
        var summary by mutableStateOf(ProfileMusicSummary(musicKey = "fixture", canEditPresentation = true))
        compose.setContent {
            MaterialTheme {
                ProfileMusicContent("https://example.invalid/fixture.mp3", summary, null,
                    { reaction -> summary = summary.withReaction(reaction.takeUnless { it == summary.reaction }) },
                    { summary = summary.copy(presentation = it) }, {})
            }
        }
        compose.onNodeWithContentDescription("LOVE: 0").performClick()
        compose.onNodeWithContentDescription("LOVE: 1").assertIsSelected().performClick()
        compose.runOnIdle { assertNull(summary.reaction); assertEquals(0, summary.total) }
        compose.onNodeWithText("Disco", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Mi música").assertIsDisplayed()
        compose.runOnIdle { assertEquals("disc", summary.presentation) }
        compose.onNodeWithText("Solo audio", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Mi música").assertDoesNotExist()
    }

    @Test fun aVisitorCannotChangeTheOwnerPresentation() {
        compose.setContent {
            MaterialTheme { ProfileMusicContent("https://example.invalid/fixture.mp3", ProfileMusicSummary(),
                null, {}, { fail("Visitor cannot choose presentation") }, {}) }
        }
        compose.onNodeWithText("Disco").assertDoesNotExist()
        compose.onNodeWithText("Solo audio").assertDoesNotExist()
        compose.onNodeWithContentDescription("LOVE: 0").assertExists()
    }
}
