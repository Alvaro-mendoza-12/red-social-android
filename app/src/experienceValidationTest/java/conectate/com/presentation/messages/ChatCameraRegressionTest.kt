package conectate.com.presentation.messages

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ChatCameraRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val captures = mutableListOf<Uri>()

    @Before fun prepareCamera() {
        assertEquals("This device audit requires camera permission", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
    }

    @After fun cleanOnlyThisTestsCaptures() {
        for (uri in captures) context.contentResolver.delete(uri, null, null)
    }

    private fun waitReady() {
        compose.waitUntil(25_000) {
            compose.onAllNodes(hasContentDescription("Vista previa de cámara") and SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, "Cámara lista")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(15_000) { compose.onAllNodes(hasContentDescription("Tomar foto") or hasContentDescription("Grabar video"))
            .fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Disabled) == null } }
    }

    private fun enableLight() {
        compose.onNodeWithContentDescription("Flash").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Flash encendido").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Flash").assertIsEnabled()
    }

    @Test fun rearFlashConfirmsCameraStateAndPhotoRemainsALocalCapture() {
        val result = AtomicReference<Uri?>(null)
        var visible by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (visible) ChatCameraCapture(setOf("image"), onClose = { visible = false }, onGallery = { visible = false },
                onCaptured = { media, _ -> captures += media.uri; result.set(media.uri); visible = false })
            else Text("Captura preparada")
        } }
        waitReady()
        compose.onNodeWithText("Video", useUnmergedTree = true).assertDoesNotExist()
        enableLight()
        compose.onNodeWithContentDescription("Tomar foto").performClick()
        compose.waitUntil(20_000) { result.get() != null }
        compose.onNodeWithText("Captura preparada").assertIsDisplayed()
        val uri = result.get()!!
        assertEquals("content", uri.scheme)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
        assertTrue(options.outWidth > 0 && options.outHeight > 0)
        assertEquals(1, captures.size)
    }

    @Test fun changingModesAndLensReleasesTheLightAndUpdatesCapabilities() {
        var visible by mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (visible) ChatCameraCapture(onClose = { visible = false }, onGallery = { visible = false },
                onCaptured = { media, _ -> captures += media.uri; fail("No shutter pressed") })
            else Text("Cámara cerrada")
        } }
        waitReady(); enableLight()
        compose.onNodeWithText("Video", useUnmergedTree = true).performClick()
        waitReady()
        compose.onNodeWithText("Flash apagado").assertIsDisplayed()
        enableLight()
        compose.onNodeWithContentDescription("Cambiar cámara").assertIsEnabled().performClick()
        waitReady()
        compose.onNodeWithContentDescription("Flash").assertIsNotEnabled()
        compose.onNodeWithText("Sin flash").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cambiar cámara").performClick()
        waitReady()
        compose.onNodeWithText("Flash apagado").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cerrar cámara").performClick()
        compose.onNodeWithText("Cámara cerrada").assertIsDisplayed()
        assertTrue(captures.isEmpty())
    }

    @Test fun galleryExitCancelsCaptureAndTurnsOffTheLight() {
        var visible by mutableStateOf(true)
        var gallery = 0
        compose.setContent { MaterialTheme {
            if (visible) ChatCameraCapture(onClose = { visible = false }, onGallery = { gallery++; visible = false },
                onCaptured = { media, _ -> captures += media.uri; fail("Opening gallery must not take a photo") })
            else Text("Galería solicitada")
        } }
        waitReady(); enableLight()
        compose.onNodeWithContentDescription("Abrir galería").performClick()
        compose.onNodeWithText("Galería solicitada").assertIsDisplayed()
        assertEquals(1, gallery)
        assertTrue(captures.isEmpty())
    }
}
