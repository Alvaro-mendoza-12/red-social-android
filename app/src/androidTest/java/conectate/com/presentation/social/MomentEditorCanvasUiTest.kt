package conectate.com.presentation.social

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.MomentMusicPresentation
import conectate.com.data.music.ApprovedMomentMusic
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MomentEditorCanvasUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var selection by mutableStateOf(MomentMusicSelection(
        ApprovedMomentMusic("qa:canvas", "Mi canción", "Novvin QA", 30_000,
            "file:///qa-unused.wav", "https://example.invalid/qa", license = "Audio propio",
            lyrics = "[00:00.00]Un día para crear\n[00:02.00]Compartimos nuestra voz\n[00:04.00]Seguimos adelante"),
        0, 6_000, 1f, 1f, presentation = MomentMusicPresentation.LABEL))
    private var hours by mutableStateOf(24f)
    private var caption by mutableStateOf("")

    @Before fun resetEditorState() {
        selection = selection.copy(
            startMs = 0,
            endMs = 6_000,
            overlayX = .5f,
            overlayY = .38f,
            overlayScale = 1f,
            overlayRotation = 0f,
            presentation = MomentMusicPresentation.LABEL,
        )
        hours = 24f
        caption = ""
    }

    private fun show(largeText: Boolean = false) {
        val file = File(compose.activity.cacheDir, "qa-moment-canvas.png")
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(0, 181, 188))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 1.5f else density.fontScale)) {
                MaterialTheme { MomentEditorView(
                    MomentEditorUiState(Uri.fromFile(file), false, caption, hours, MomentAudienceOption.EVERYONE,
                        emptyList(), emptyList(), false, true, "", "#FFFFFFFF", "sans", 1f, .5f, .38f,
                        selection, MusicCatalogUiState(), 6_000, false, true),
                    MomentEditorActions(onCaptionChange = { caption = it }, onDurationChange = { hours = it },
                        onAudienceChange = {}, onSearchAudience = {}, onToggleAudienceUser = {}, onAllowRepliesChange = {},
                        onOverlayTextChange = {}, onOverlayColorChange = {}, onOverlayFontChange = {}, onOverlayScaleChange = {},
                        onOverlayPositionChange = { _, _ -> }, onSearchMusic = { _, _, _ -> }, onLoadMoreMusic = {},
                        onToggleMusicFavorite = {}, onReportMusic = {}, onNotInterestedMusic = {}, onMusicPreviewStarted = {},
                        onOpenCommunityMusicUpload = {}, onOpenDeviceMusic = {}, onMusicSelected = { selection = it },
                        onRemoveMusic = {}, onBack = {}, onPost = {})) }
            }
        }
        compose.waitForIdle()
    }

    @Test fun musicStaysInsideMediaAndAwayFromCaptionWithLargeText() {
        show(largeText = true)
        val sticker = compose.onNodeWithTag("moment_music_canvas_sticker").fetchSemanticsNode().boundsInRoot
        val canvas = compose.onNodeWithTag("moment_editor_media_canvas").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("moment_editor_footer").fetchSemanticsNode().boundsInRoot
        assertTrue("Music overlaps caption controls", sticker.bottom <= footer.top + 1f)
        assertTrue(sticker.top >= canvas.top && sticker.bottom <= canvas.bottom + 1f)
        compose.onNodeWithContentDescription("Publicar").assertIsDisplayed()
        capture("qa-moment-editor-label.png")
    }
    @Test fun previewHasAnExplicitPauseAndResumeControl() {
        show()
        compose.onNodeWithContentDescription("Pausar vista previa").performClick()
        compose.onNodeWithContentDescription("Reproducir vista previa").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Pausar vista previa").assertIsDisplayed()
    }

    @Test fun draggingMovesTheMusicInsteadOfTheCaption() {
        show()
        val before = selection.overlayX
        compose.onNodeWithTag("moment_music_canvas_sticker").performTouchInput { swipe(center, center + Offset(40f, -50f), 400) }
        compose.runOnIdle { assertTrue(selection.overlayX > before); assertTrue(selection.overlayY < .5f); assertEquals("", caption) }
    }
    @Test fun twoFingerGestureChangesMusicScaleAndRotation() {
        selection = selection.copy(overlayScale = .7f)
        show()
        compose.onNodeWithTag("moment_music_canvas_sticker").performTouchInput {
            val origin = center
            down(0, origin - Offset(60f, 0f)); down(1, origin + Offset(60f, 0f))
            moveTo(0, origin - Offset(74f, 24f), 50)
            moveTo(1, origin + Offset(74f, 24f), 50)
            up(0); up(1)
        }
        compose.runOnIdle { assertTrue(selection.overlayScale > .7f); assertTrue(selection.overlayRotation > 5f) }
    }
    @Test fun lyricsUseActualCuesAndRotationWithoutCoveringFooter() {
        selection = selection.copy(presentation = MomentMusicPresentation.LYRICS, startMs = 2_100, endMs = 8_100,
            overlayRotation = 18f)
        show()
        compose.onNodeWithTag("moment_music_active_lyric").assertTextEquals("Compartimos nuestra voz")
        compose.onNodeWithText("Un día para crear").assertIsDisplayed()
        compose.onNodeWithText("Seguimos adelante").assertIsDisplayed()
        capture("qa-moment-editor-lyrics.png")
    }
    @Test fun lifetimePresetsSaveAndCancelDoesNotChangeTheMoment() {
        show()
        compose.onNodeWithTag("moment_lifetime_button").performClick()
        compose.onNodeWithTag("moment_lifetime_12h").performClick()
        compose.onNodeWithText("Guardar").performClick()
        compose.runOnIdle { assertEquals(12f, hours, .01f) }
        compose.onNodeWithTag("moment_lifetime_button").performClick()
        compose.onNodeWithTag("moment_lifetime_6h").performClick()
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle { assertEquals(12f, hours, .01f) }
    }
    @Test fun captionExpansionKeepsMusicOutsideTheControls() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("Primera línea\nSegunda línea\nTercera línea\nCuarta línea")
        compose.waitForIdle()
        val sticker = compose.onNodeWithTag("moment_music_canvas_sticker").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("moment_editor_footer").fetchSemanticsNode().boundsInRoot
        assertTrue("Music sticker overlaps expanded caption controls: sticker=$sticker footer=$footer", sticker.bottom <= footer.top + 1f)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val roots = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch>()
            instrumentation.runOnMainSync {
                val views = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown && it.isHardwareAccelerated }
                val latch = java.util.concurrent.CountDownLatch(views.size); roots.set(latch)
                views.forEach { view -> view.viewTreeObserver.registerFrameCommitCallback { latch.countDown() }; view.postInvalidateOnAnimation() }
            }
            assertTrue(roots.get().await(3, java.util.concurrent.TimeUnit.SECONDS))
        }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(compose.activity.externalCacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
