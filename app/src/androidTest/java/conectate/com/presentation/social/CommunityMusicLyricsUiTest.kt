package conectate.com.presentation.social

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CommunityMusicLyricsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun optionalImportIsDisabledDuringReadingAndOpensWhenReady() {
        var reading by mutableStateOf(true)
        var opens = 0
        compose.setContent { MaterialTheme { CommunityMusicLyricsSection(
            lyrics = "", durationMs = 6_000, reading = reading, error = null, enabled = true,
            onAdd = { opens++ }, onRemove = {}) } }
        compose.onNodeWithTag("community_music_add_lyrics").assertIsNotEnabled()
        compose.runOnIdle { reading = false }
        compose.onNodeWithTag("community_music_add_lyrics").assertIsEnabled().performClick()
        assertEquals(1, opens)
    }

    @Test fun actualTimedTextShowsPreviewAndCanBeRemovedWithLargeText() {
        var lyrics by mutableStateOf("[00:00]Compartimos nuestra voz\n[00:02]Seguimos creando")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme { CommunityMusicLyricsSection(lyrics = lyrics, durationMs = 6_000, reading = false,
                    error = "No se pudo leer otra letra", enabled = true, onAdd = {}, onRemove = { lyrics = "" }) }
            }
        }
        compose.onNodeWithTag("community_music_lyrics_added").assertIsDisplayed()
        compose.onNodeWithText("Compartimos nuestra voz").assertIsDisplayed()
        compose.onNodeWithText("No se pudo leer otra letra").assertIsDisplayed()
        compose.onNodeWithText("Quitar letra").performClick()
        compose.onNodeWithTag("community_music_lyrics_added").assertDoesNotExist()
        compose.onNodeWithTag("community_music_add_lyrics").assertIsDisplayed()
    }
}
