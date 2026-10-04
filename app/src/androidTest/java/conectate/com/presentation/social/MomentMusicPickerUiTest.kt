package conectate.com.presentation.social

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.music.ApprovedMomentMusic
import conectate.com.data.model.MomentMusicPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Native UI and one-tap playback, using an owned local WAV with no network or session changes. */
@RunWith(AndroidJUnit4::class)
class MomentMusicPickerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun track(index: Int = 1, seconds: Int = 12): ApprovedMomentMusic {
        val samples = 16_000 * seconds
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort(0) }
        val file = File(compose.activity.cacheDir, "qa-music-picker.wav")
        file.writeBytes(bytes.array())
        return ApprovedMomentMusic("qa:$index", "Canción de prueba $index", "Novvin QA", seconds * 1_000L,
            android.net.Uri.fromFile(file).toString(), "https://example.invalid/qa",
            source = "novvin_community", license = "Audio propio")
    }

    private fun show(
        state: MusicCatalogUiState,
        initial: MomentMusicSelection? = null,
        largeText: Boolean = false,
        onPreview: () -> Unit = {},
        onSearch: (String) -> Unit = {},
        onSelect: (MomentMusicSelection) -> Unit = {},
        durationMs: Long? = 6_000,
        originalAudio: Boolean = true,
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 1.5f else density.fontScale)) {
                MaterialTheme { MomentMusicPickerDialog(
                    state.copy(section = "community"), durationMs, originalAudio, initial,
                    onSearch = { query, _, _ -> onSearch(query) }, onLoadMore = {},
                    onToggleFavorite = {}, onReport = {}, onNotInterested = {},
                    onPreviewStarted = { onPreview() }, onOpenCommunityUpload = {},
                    onOpenDeviceMusic = {}, onDismiss = {}, onRemove = {}, onSelect = onSelect,
                ) }
            }
        }
    }

    private fun screenshot(name: String) {
        File(compose.activity.externalCacheDir, name).outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun communityOpensWithSearchAndCanClearTheQuery() {
        var query: String? = null
        show(MusicCatalogUiState(items = listOf(track())), onSearch = { query = it })
        compose.onNodeWithText("Música de la comunidad").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Piano")
        assertEquals("Piano", query)
        compose.onNodeWithContentDescription("Borrar búsqueda").performClick()
        assertEquals("", query)
        screenshot("qa-music-catalog.png")
    }

    @Test fun oneTapOnTheSongStartsPlaybackAndShowsConfirmation() {
        val song = track()
        var previews = 0
        var selection: MomentMusicSelection? = null
        show(MusicCatalogUiState(items = listOf(song)), onPreview = { previews++ }, onSelect = { selection = it })
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(song.title))
        compose.onNodeWithText(song.title).performClick()
        compose.onNodeWithText("Usar audio").assertIsDisplayed()
        compose.waitUntil(6_000) { compose.onAllNodesWithContentDescription("Pausar").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, previews)
        compose.onNodeWithText("Usar audio").performClick()
        assertNotNull(selection)
        assertEquals(song.id, selection!!.track.id)
        assertEquals(6_000L, selection!!.endMs - selection!!.startMs)
    }

    @Test fun confirmationStaysVisibleWithLongTitlesAndLargeText() {
        val song = track().copy(title = "Una canción con un nombre largo que no debe ocultar los controles de selección")
        show(MusicCatalogUiState(), MomentMusicSelection(song, 0, 6_000, 1f, 1f), largeText = true)
        compose.onNodeWithText("Usar audio").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Escuchar").assertIsDisplayed()
        screenshot("qa-music-selection-large-text.png")
        compose.onNodeWithContentDescription("Opciones de música").performClick()
        compose.onNodeWithText("Información de la canción").performClick()
        compose.onNodeWithText("Ver licencia").assertIsDisplayed()
        compose.onNodeWithText("Listo").performClick()
        compose.onNodeWithText("Usar audio").assertIsDisplayed()
    }

    @Test fun aFailedCatalogOffersRetryWithoutHidingItsSearch() {
        var attempts = 0
        show(MusicCatalogUiState(error = "No se pudo cargar la música"), onSearch = { attempts++ })
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Reintentar"))
        compose.onNodeWithText("Reintentar").assertIsDisplayed().performClick()
        assertEquals(1, attempts)
    }

    @Test fun threePresentationsConfirmTheStyleAndRetainTheExistingMix() {
        val song = track()
        var confirmed: MomentMusicSelection? = null
        show(MusicCatalogUiState(), MomentMusicSelection(song, 0, 6_000, .35f, .65f,
            fadeInMs = 300, fadeOutMs = 500, duckOriginalAudio = true), onSelect = { confirmed = it })
        compose.onNodeWithText("Volumen de la música 100%").assertDoesNotExist()
        compose.onNodeWithTag("music_presentation_option_card").assertIsSelected()
        compose.onNodeWithTag("music_presentation_option_label").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("moment_music_presentation_label").assertExists()
        compose.onNodeWithTag("music_presentation_option_audio").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("moment_music_presentation_label").assertDoesNotExist()
        compose.onNodeWithText("Usar audio").assertIsDisplayed()
        screenshot("qa-music-audio-only.png")
        compose.onNodeWithText("Usar audio").performClick()
        assertEquals(MomentMusicPresentation.AUDIO_ONLY, confirmed!!.presentation)
        assertEquals(.35f, confirmed!!.musicVolume, .001f)
        assertEquals(.65f, confirmed!!.originalAudioVolume, .001f)
        assertEquals(300L, confirmed!!.fadeInMs)
        assertEquals(500L, confirmed!!.fadeOutMs)
        assertEquals(true, confirmed!!.duckOriginalAudio)
    }

    @Test fun photoDurationStaysWithinTheAuthoritativeSixSeconds() {
        val song = track()
        var confirmed: MomentMusicSelection? = null
        show(MusicCatalogUiState(), MomentMusicSelection(song, 0, 6_000, 1f, 1f),
            durationMs = 60_000, originalAudio = false, onSelect = { confirmed = it })
        compose.onNodeWithText("6 s").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Usar audio").performClick()
        assertEquals(6_000L, confirmed!!.endMs - confirmed!!.startMs)
    }

    @Test fun durationOptionsStayWithinTheVideoAndConfirmationUsesTheChosenLength() {
        val song = track(seconds = 60)
        var confirmed: MomentMusicSelection? = null
        show(MusicCatalogUiState(), MomentMusicSelection(song, 0, 6_000, 1f, 1f),
            durationMs = 25_000, onSelect = { confirmed = it })
        compose.onNodeWithText("6 s").performScrollTo().performClick()
        compose.onNodeWithText("15 segundos").assertIsDisplayed()
        compose.onNodeWithText("25 segundos").assertIsDisplayed()
        compose.onNodeWithText("60 segundos").assertDoesNotExist()
        compose.onNodeWithText("25 segundos").performClick()
        compose.onNodeWithText("Usar audio").performClick()
        assertEquals(25_000L, confirmed!!.endMs - confirmed!!.startMs)
    }
}
