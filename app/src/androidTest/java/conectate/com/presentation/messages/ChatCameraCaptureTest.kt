package conectate.com.presentation.messages

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.core.content.ContextCompat
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Captures actual device media into this app's private QA cache; sends nothing to another account. */
@SdkSuppress(minSdkVersion = 28)
class ChatCameraCaptureTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownedUris = mutableListOf<Uri>()
    private var closeCamera: () -> Unit = {}

    @Before fun allowCapturePermissions() {
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).forEach { permission ->
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, permission)
            }
        }
    }

    @After fun cleanup() {
        compose.runOnIdle { closeCamera() }
        ownedUris.forEach { uri ->
            if (uri.scheme == "content" && uri.authority == "${context.packageName}.provider") {
                context.contentResolver.delete(uri, null, null)
            } else if (uri.scheme == "file") {
                val file = File(requireNotNull(uri.path)).canonicalFile
                if (file.toPath().startsWith(context.cacheDir.canonicalFile.toPath())) file.delete()
            }
        }
    }

    @Test fun rearPhotoAndFrontPhotoProduceReadableImages() {
        val result = AtomicReference<OutgoingMessageMedia?>()
        showCamera { item, _ -> ownedUris.add(item.uri); result.set(item) }
        waitReady("Tomar foto")
        compose.onNodeWithContentDescription("Tomar foto").performClick()
        compose.waitUntil(20_000) { result.get() != null }
        assertReadableImage(requireNotNull(result.get()))
        result.set(null)
        compose.onNodeWithContentDescription("Cambiar cámara").performClick()
        waitReady("Tomar foto")
        compose.onNodeWithContentDescription("Tomar foto").performClick()
        compose.waitUntil(20_000) { result.get() != null }
        val captured = requireNotNull(result.get())
        assertReadableImage(captured)
    }

    private fun assertReadableImage(captured: OutgoingMessageMedia) {
        assertEquals("image", captured.type)
        assertEquals("content", captured.uri.scheme)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(captured.uri).use { BitmapFactory.decodeStream(it, null, options) }
        assertTrue(options.outWidth > 0 && options.outHeight > 0)
    }

    @Test fun videoRecordsRealFramesAndAudioThenStops() {
        val result = AtomicReference<Pair<OutgoingMessageMedia, Long>?>()
        showCamera { item, duration -> ownedUris.add(item.uri); result.set(item to duration) }
        waitReady("Tomar foto")
        compose.onNodeWithText("Video").performClick()
        waitReady("Grabar video")
        compose.onNodeWithContentDescription("Grabar video").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Detener grabación").fetchSemanticsNodes().isNotEmpty() }
        val started = SystemClock.elapsedRealtime()
        compose.waitUntil(12_000) {
            val timer = compose.onAllNodesWithContentDescription("Duración de grabación").fetchSemanticsNodes().singleOrNull()
            (timer?.config?.get(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)
                ?.substringBefore(' ')?.toIntOrNull() ?: 0) >= 2
        }
        assertTrue("Video preparation exceeded the test bound", SystemClock.elapsedRealtime() - started < 12_000)
        compose.onNodeWithContentDescription("Detener grabación").performClick()
        compose.waitUntil(20_000) { result.get() != null }
        val (item, duration) = requireNotNull(result.get())
        assertEquals("video", item.type)
        assertTrue(duration >= 1_500 && duration <= CHAT_CAMERA_MAX_DURATION_MS)
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, item.uri)
            assertTrue(requireNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)).toInt() > 0)
            assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            requireNotNull(retriever.getFrameAtTime(1_000_000)).recycle()
        }
    }

    @Test fun closingARecordingDoesNotDeliverOrLeaveItsFile() {
        val directory = File(context.cacheDir, "file-provider/chat-captures")
        val original = directory.list()?.toSet().orEmpty()
        val result = AtomicReference<OutgoingMessageMedia?>()
        showCamera { item, _ -> ownedUris.add(item.uri); result.set(item) }
        waitReady("Tomar foto")
        compose.onNodeWithText("Video").performClick()
        waitReady("Grabar video")
        compose.onNodeWithContentDescription("Grabar video").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Detener grabación").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Cerrar cámara").performClick()
        compose.waitUntil(15_000) { directory.list()?.toSet().orEmpty() == original }
        assertNull(result.get())
    }

    @Test fun cameraPhotoGoesThroughEditorAndOnlySendsAfterConfirmation() {
        val sent = AtomicReference<List<OutgoingMessageMedia>?>()
        var shown by mutableStateOf(true)
        closeCamera = { shown = false }
        compose.setContent {
            MaterialTheme {
                if (shown) MessageMediaComposer(initialStep = "camera", isSending = false,
                    onDismiss = { shown = false }, onSend = { items, _ ->
                        ownedUris.addAll(items.map { it.uri }); sent.set(items); shown = false
                    })
            }
        }
        waitReady("Tomar foto")
        compose.onNodeWithContentDescription("Tomar foto").performClick()
        // The editor appears before its full photo snapshot is ready. A disabled
        // Send button intentionally ignores clicks; wait for the actual ready state.
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Enviar").fetchSemanticsNodes()
            .any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        assertNull(sent.get())
        compose.onNodeWithContentDescription("Enviar").performClick()
        compose.waitUntil(20_000) { sent.get() != null }
        assertEquals(1, sent.get()?.size)
        assertEquals("image", sent.get()?.single()?.type)
        context.contentResolver.openInputStream(requireNotNull(sent.get()).single().uri).use { input ->
            assertTrue(requireNotNull(input).read() >= 0)
        }
    }

    private fun showCamera(onCaptured: (OutgoingMessageMedia, Long) -> Unit) {
        var shown by mutableStateOf(true)
        closeCamera = { shown = false }
        compose.setContent {
            MaterialTheme {
                if (shown) ChatCameraCapture(onClose = { shown = false }, onGallery = { shown = false }, onCaptured = onCaptured)
            }
        }
    }

    private fun waitReady(action: String) {
        compose.waitUntil(25_000) {
            compose.onAllNodesWithContentDescription(action).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Cámara lista"))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(action).assertIsEnabled()
    }
}
