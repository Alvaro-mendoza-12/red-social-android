package conectate.com.presentation.messages

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraGalleryShortcutDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val owned = mutableListOf<Uri>()

    private fun addPhoto(color: Int, pending: Boolean = false): Uri {
        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "qa-camera-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        owned += uri
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { context.contentResolver.openOutputStream(uri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        if (!pending) context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun waitForColor(green: Boolean) {
        compose.waitUntil(10_000) {
            val pixels = runCatching { compose.onNodeWithContentDescription("Abrir galería").captureToImage().toPixelMap() }.getOrNull()
            pixels?.let {
                val color = it[it.width / 2, it.height / 2]
                if (green) color.green > .8f && color.red < .2f else color.red > .8f && color.blue > .8f && color.green < .2f
            } == true
        }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun latestAccessiblePhotoUpdatesWhileTheCameraIsOpenAndTheShortcutStillOpensGallery() {
        addPhoto(android.graphics.Color.MAGENTA)
        var opened = 0
        compose.setContent { MaterialTheme { CameraGalleryShortcut(setOf("image", "video"), true, true) { opened++ } } }
        waitForColor(false)
        val newest = addPhoto(android.graphics.Color.GREEN)
        assertEquals(newest, runBlocking { loadLatestCameraGalleryItem(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) })
        waitForColor(true)
        compose.onNodeWithContentDescription("Abrir galería").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test @SdkSuppress(minSdkVersion = 29)
    fun unfinishedGalleryWritesDoNotReplaceTheLatestUsablePhoto() {
        val complete = addPhoto(android.graphics.Color.MAGENTA)
        addPhoto(android.graphics.Color.GREEN, pending = true)
        assertEquals(complete, runBlocking { loadLatestCameraGalleryItem(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) })
        compose.setContent { MaterialTheme { CameraGalleryShortcut(setOf("image"), true, true) {} } }
        waitForColor(false)
    }

    @Test fun shutterStaysCenteredWithUnequalSideControlsOnNarrowAndWideLayouts() {
        val width = mutableStateOf(280.dp)
        compose.setContent {
            Box(Modifier.width(width.value)) {
                CameraCaptureActionRow(
                    gallery = { Box(Modifier.size(56.dp).testTag("gallery")) },
                    shutter = { Box(Modifier.size(80.dp).testTag("shutter")) },
                    switchCamera = { Box(Modifier.size(48.dp).testTag("switch")) },
                )
            }
        }
        for (size in listOf(280.dp, 360.dp)) {
            compose.runOnIdle { width.value = size }
            compose.waitForIdle()
            val row = compose.onNodeWithTag("camera_actions").fetchSemanticsNode().boundsInRoot
            val shutter = compose.onNodeWithTag("shutter").fetchSemanticsNode().boundsInRoot
            val gallery = compose.onNodeWithTag("gallery").fetchSemanticsNode().boundsInRoot
            val switch = compose.onNodeWithTag("switch").fetchSemanticsNode().boundsInRoot
            assertEquals(row.center.x, shutter.center.x, 1f)
            assertEquals(gallery.center.y, shutter.center.y, 1f)
            assertEquals(switch.center.y, shutter.center.y, 1f)
            assertTrue(gallery.right <= shutter.left && shutter.right <= switch.left)
        }
    }

    @After fun removeOnlyOwnedFixtures() { owned.forEach { context.contentResolver.delete(it, null, null) } }
}
