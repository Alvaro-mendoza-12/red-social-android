package conectate.com.presentation.social

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.MomentMusicPresentation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MomentMusicPresentationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cardShowsTitleAndArtistWithLargeTextAndWithoutCover() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme { MomentMusicPresentationPreview(MomentMusicPresentation.CARD,
                    "Una canción para compartir", "Novvin QA", null) }
            }
        }
        compose.onNodeWithTag("moment_music_presentation_card").assertIsDisplayed()
        compose.onNodeWithText("Una canción para compartir").assertIsDisplayed()
        compose.onNodeWithText("Novvin QA").assertIsDisplayed()
    }

    @Test fun labelUsesCompactPresentation() {
        compose.setContent { MaterialTheme { MomentMusicPresentationPreview(
            MomentMusicPresentation.LABEL, "Canción", "Artista", null) } }
        compose.onNodeWithTag("moment_music_presentation_label").assertIsDisplayed()
        compose.onNodeWithText("Canción").assertIsDisplayed()
    }

    @Test fun audioOnlyLeavesMediaFreeOfPresentation() {
        compose.setContent { MaterialTheme { MomentMusicPresentationPreview(
            MomentMusicPresentation.AUDIO_ONLY, "Canción", "Artista", null) } }
        compose.onNodeWithText("Canción").assertDoesNotExist()
        compose.onNodeWithTag("moment_music_presentation_card").assertDoesNotExist()
        compose.onNodeWithTag("moment_music_presentation_label").assertDoesNotExist()
    }
}
