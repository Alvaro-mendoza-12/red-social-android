package conectate.com

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.Post
import conectate.com.presentation.components.PostCard
import conectate.com.presentation.videos.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Local synthetic inputs only; no login, user messages, LIVE or payment writes. */
class Audit99MobileUiTest {
    @get:Rule val compose = createComposeRule()
    private val post = Post(id = "audit99-post", userId = "audit99-author", userName = "Novvin QA",
        content = "Publicación de prueba", remixAllowed = true)

    private fun showPost(value: Post = post, onSave: (() -> Unit)? = null) {
        compose.setContent { MaterialTheme { Box(Modifier.fillMaxSize()) {
            PostCard(value, onLikeClick = {}, onReactionSelect = {}, onReactionSummaryClick = {},
                onCommentClick = {}, onShareClick = {}, onUserClick = {}, showReportAction = true,
                onSaveClick = onSave)
        } } }
    }

    @Test fun unavailableActionsNeverOfferAnEmptySuccessFlow() {
        showPost()
        compose.onNodeWithContentDescription("Opciones").performClick()
        compose.onNodeWithText("Reportar").assertIsDisplayed()
        for (label in listOf("Guardar en colección", "¿Por qué veo esto?", "No me interesa", "Crear remix")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test fun connectedSaveActionIsOfferedAndRunsOnce() {
        var saved = 0
        showPost(onSave = { saved++ })
        compose.onNodeWithContentDescription("Opciones").performClick()
        compose.onNodeWithText("Guardar en colección").performClick()
        compose.runOnIdle { assertEquals(1, saved) }
    }

    @Test fun aLocalPostVideoOpensTheVideoViewerInsteadOfAnImageDecoder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File(instrumentation.targetContext.cacheDir, "audit99-post-${UUID.randomUUID()}.mp4")
        try {
            instrumentation.context.assets.open("video-cache-smoke.mp4").use { from -> source.outputStream().use { from.copyTo(it) } }
            showPost(post.copy(mediaUrls = listOf(Uri.fromFile(source).toString())))
            compose.onNodeWithContentDescription("Reproducir video de la publicación").performClick()
            compose.onNodeWithContentDescription("Cerrar").assertIsDisplayed()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("video_viewer_ready").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("video_viewer_error").assertDoesNotExist()
            compose.onNodeWithContentDescription("Cerrar").performClick()
        } finally { source.delete() }
    }

    @Test fun overlayPositionStaysRelativeToTheVideoAcrossPreviewSizes() {
        var width by mutableStateOf(320)
        val clip = EditorClip(101, type = ClipType.STICKER, resId = android.R.drawable.star_on,
            startMs = 0, durationMs = 3_000, x = 250f, y = 250f)
        val timeline = EditorTimeline(tracks = listOf(EditorTrack(id = 1, type = TrackType.STICKER, clips = listOf(clip))))
        compose.setContent { MaterialTheme { Box(Modifier.width(width.dp).height(300.dp)) {
            EditorOverlayPreview(timeline, 0, null, 9f / 16f, { _, _, _ -> }, {})
        } } }
        fun checkPosition() {
            val canvas = compose.onNodeWithTag("editor_overlay_canvas").fetchSemanticsNode().boundsInRoot
            val sticker = compose.onNodeWithTag("editor_overlay_101").fetchSemanticsNode().boundsInRoot
            assertEquals(.25f, (sticker.left - canvas.left) / canvas.width, .015f)
            assertEquals(.25f, (sticker.top - canvas.top) / canvas.height, .015f)
        }
        checkPosition()
        compose.runOnIdle { width = 200 }
        compose.waitForIdle()
        checkPosition()
    }

    @Test fun aContinuousOverlayDragAccumulatesEveryMovement() {
        val clip = EditorClip(102, type = ClipType.STICKER, resId = android.R.drawable.ic_dialog_info,
            startMs = 0, durationMs = 3_000, x = 250f, y = 250f)
        var timeline by mutableStateOf(EditorTimeline(tracks = listOf(EditorTrack(id = 1, type = TrackType.STICKER, clips = listOf(clip)))))
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp).height(300.dp)) {
            EditorOverlayPreview(timeline, 0, null, 1f, { id, x, y ->
                timeline = timeline.updateClip(id) { it.copy(x = x, y = y) }
            }, {})
        } } }
        val canvasWidth = compose.onNodeWithTag("editor_overlay_canvas").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("editor_overlay_102").performTouchInput {
            swipe(center, center + Offset(canvasWidth * .2f, 0f), 700)
        }
        compose.runOnIdle { assertTrue("Only the last gesture delta was saved", timeline.tracks.single().clips.single().x > 370f) }
    }

    @Test fun nativeExportKeepsStickerAndTextAtTheirNormalizedPositions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val marker = UUID.randomUUID().toString()
        val source = File(context.cacheDir, "audit99-overlay-source-$marker.mp4")
        val lut = File(context.cacheDir, "file-provider/crops/audit99-$marker.cube")
        var model: VideoEditorViewModel? = null
        var output: File? = null
        var lutId: String? = null
        try {
            instrumentation.context.assets.open("video-cache-smoke.mp4").use { from -> source.outputStream().use { from.copyTo(it) } }
            lut.parentFile!!.mkdirs()
            lut.writeText("TITLE \"Rojo QA\"\nLUT_3D_SIZE 2\n" + List(8) { "1 0 0" }.joinToString("\n"))
            instrumentation.runOnMainSync {
                model = VideoEditorViewModel(SavedStateHandle())
                model!!.getPlayer(context)
                model!!.setInitialVideo(context, Uri.fromFile(source))
            }
            val editor = model!!
            compose.waitUntil(20_000) { editor.timeline.value.tracks.flatMap { it.clips }.any { it.type == ClipType.VIDEO } }
            val video = editor.timeline.value.tracks.flatMap { it.clips }.first { it.type == ClipType.VIDEO }
            instrumentation.runOnMainSync {
                editor.importColorLut(context, video.id, FileProvider.getUriForFile(context, "${context.packageName}.provider", lut))
            }
            compose.waitUntil(20_000) { !editor.isImportingLut.value }
            lutId = editor.timeline.value.tracks.flatMap { it.clips }.first { it.type == ClipType.VIDEO }.customLutId
            assertNotNull(lutId)
            instrumentation.runOnMainSync {
                editor.addStickerOverlay(context, android.R.drawable.star_on, 0)
                val sticker = editor.timeline.value.tracks.flatMap { it.clips }.first { it.type == ClipType.STICKER }
                editor.updateClipPosition(sticker.id, 250f, 250f)
                editor.addTextOverlay("QA", 0)
                val text = editor.timeline.value.tracks.flatMap { it.clips }.first { it.type == ClipType.TEXT }
                editor.updateClipPosition(text.id, 750f, 500f)
            }
            compose.setContent { MaterialTheme {
                VideoEditorScreen(rememberNavController(), Uri.fromFile(source), viewModel = editor)
            } }
            val done = CountDownLatch(1)
            var exported: Uri? = null
            var error: String? = null
            instrumentation.runOnMainSync { editor.exportVideo(context, { exported = it; done.countDown() }, { error = it; done.countDown() }) }
            assertTrue("Native overlay export timed out", done.await(90, TimeUnit.SECONDS))
            assertNull(error)
            val uri = checkNotNull(exported)
            output = resolveOwnedEditorExportFile(context, uri)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(context, uri)
                val frame = checkNotNull(reader.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
                val points = mutableListOf<Pair<Int, Int>>()
                for (y in 0 until frame.height step 2) for (x in 0 until frame.width step 2) {
                    val pixel = frame.getPixel(x, y)
                    if (Color.green(pixel) > 100 || Color.blue(pixel) > 100) points += x to y
                }
                val sticker = points.filter { it.first < frame.width * .6f }
                val text = points.filter { it.first >= frame.width * .6f }
                assertTrue("Sticker missing from exported frame", sticker.size > 10)
                assertTrue("Text missing from exported frame", text.size > 5)
                assertTrue(sticker.minOf { it.first } >= frame.width * .25f - 8)
                assertTrue(sticker.maxOf { it.first } <= frame.width * (.25f + 1f / 6f) + 8)
                assertTrue(sticker.minOf { it.second } >= frame.height * .25f - 8)
                assertTrue(sticker.maxOf { it.second } <= frame.height * .25f + frame.width / 6f + 8)
                assertTrue(text.minOf { it.first } >= frame.width * .75f - 8)
                assertTrue(text.minOf { it.second } >= frame.height * .5f - 8)
                val folder = context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
                File(folder, "audit99-overlay-export-frame.png").outputStream().use {
                    frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                frame.recycle()
            } finally { reader.release() }
        } finally {
            instrumentation.runOnMainSync { model?.let { ViewModelStore().apply { put("qa", it); clear() } } }
            source.delete(); lut.delete(); output?.delete()
            lutId?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { File(context.filesDir, "editor-color-luts/$it.cube").delete() }
        }
    }
}
