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

    private fun track(index: Int = 1): ApprovedMomentMusic {
        val samples = 16_000 * 12
        val bytes = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(16_000).putInt(32_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { bytes.putShort(0) }
        val file = File(compose.activity.cacheDir, "qa-music-picker.wav")
        file.writeBytes(bytes.array())
        return ApprovedMomentMusic("qa:$index", "Canción de prueba $index", "Novvin QA", 12_000,
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
    ) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 1.5f else density.fontScale)) {
                MaterialTheme { MomentMusicPickerDialog(
                    state, 6_000, true, initial,
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
        compose.onNodeWithText("Ver atribución y licencia").performScrollTo()
        compose.onNodeWithText("Usar audio").assertIsDisplayed()
    }

    @Test fun aFailedCatalogOffersRetryWithoutHidingItsSearch() {
        var attempts = 0
        show(MusicCatalogUiState(error = "No se pudo cargar la música"), onSearch = { attempts++ })
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Reintentar"))
        compose.onNodeWithText("Reintentar").assertIsDisplayed().performClick()
        assertEquals(1, attempts)
    }
}
