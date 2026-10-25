package conectate.com.presentation.social

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MomentAuthorHeaderTest {
    @get:Rule val compose = createComposeRule()

    private val moment = Moment(
        userId = "header-test", mediaUrl = "", mediaType = "image",
        userDisplayName = "Nombre del autor muy largo para una pantalla pequeña",
        musicTitle = "Título de canción muy largo para una pantalla pequeña",
        musicArtist = "Artista", musicAudioUrl = "https://example.invalid/audio.mp3",
        musicLicenseUrl = "https://example.invalid/license",
    )

    @Test fun authorAndMusicOpenTheirOwnDestinations() {
        var profile: String? = null
        var attribution: String? = null
        compose.setContent {
            MaterialTheme {
                MomentAuthorHeader(
                    moment, "Ahora", { attribution = it },
                    onOpenProfile = { profile = it },
                )
            }
        }
        compose.onNodeWithTag("moment_author_profile").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(moment.userId, profile)
            assertEquals(null, attribution)
        }
        compose.onNodeWithTag("moment_music_attribution").performClick()
        compose.runOnIdle { assertEquals(moment.musicLicenseUrl, attribution) }
    }

    @Test fun unavailableAuthorCannotNavigateToAnEmptyProfile() {
        compose.setContent {
            MaterialTheme {
                MomentAuthorHeader(moment.copy(userId = ""), "Ahora", {}, onOpenProfile = {})
            }
        }
        compose.onNodeWithTag("moment_author_profile").assertIsNotEnabled()
    }

    @Test fun longMusicTitleStaysBelowAuthorWithLargeFontAndRemainsClickable() {
        var opened: String? = null
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.8f)) {
                MaterialTheme {
                    Box(Modifier.width(240.dp)) {
                        MomentAuthorHeader(moment, "Ahora", { opened = it })
                    }
                }
            }
        }
        val author = compose.onNodeWithText(moment.userDisplayName!!).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val timestamp = compose.onNodeWithText("Ahora").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val music = compose.onNodeWithTag("moment_music_attribution").assertIsDisplayed()
        assertTrue(music.fetchSemanticsNode().boundsInRoot.top > author.bottom)
        assertTrue(music.fetchSemanticsNode().boundsInRoot.top > timestamp.bottom)
        music.performClick()
        compose.runOnIdle { assertEquals(moment.musicLicenseUrl, opened) }
    }

    @Test fun momentWithoutMusicDoesNotReserveAnAttributionRow() {
        compose.setContent {
            MaterialTheme { MomentAuthorHeader(moment.copy(musicAudioUrl = null), "Ahora", {}) }
        }
        compose.onNodeWithText(moment.userDisplayName!!).assertIsDisplayed()
        compose.onNodeWithTag("moment_music_attribution").assertDoesNotExist()
    }
}
