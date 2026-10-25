package conectate.com.presentation.messages

import android.Manifest
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Uses only generated local media and never sends a message to an account. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class MessageMediaComposerLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ownedFiles = mutableListOf<File>()
    private val ownedGallery = mutableListOf<Uri>()
    private val sent = AtomicReference<Pair<List<OutgoingMessageMedia>, String>?>()

    @After fun cleanup() {
        ownedFiles.forEach(File::delete)
        ownedGallery.forEach { context.contentResolver.delete(it, null, null) }
        sent.get()?.first.orEmpty().forEach { media ->
            if (media.uri.scheme == "file") {
                val file = File(requireNotNull(media.uri.path)).canonicalFile
                if (file.toPath().startsWith(context.cacheDir.canonicalFile.toPath())) file.delete()
            }
        }
    }

    private fun image(): Uri {
        val file = File(context.cacheDir, "qa_layout_${UUID.randomUUID()}.png").also(ownedFiles::add)
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.rgb(48, 120, 210))
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return file.toUri()
    }

    private fun show(uri: Uri, maxItems: Int = 30) {
        compose.setContent {
            MaterialTheme {
                MessageMediaComposer(
                    initialSelectedUris = listOf(uri to "image"), initialStep = "editor", maxItems = maxItems,
                    isSending = false, onDismiss = {}, onSend = { media, caption -> sent.set(media to caption) },
                )
            }
        }
        waitReady()
    }

    private fun waitReady() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Enviar").fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(SemanticsProperties.Disabled) == false
        }
    }

    @Test fun footerAndPreviewNeverOverlapAndSendStaysVisibleWhileTyping() {
        show(image())
        fun checkGeometry() {
            val preview = compose.onNodeWithTag("composer_preview").fetchSemanticsNode().boundsInRoot
            val tools = compose.onNodeWithTag("composer_tools").fetchSemanticsNode().boundsInRoot
            val footer = compose.onNodeWithTag("composer_footer").fetchSemanticsNode().boundsInRoot
            val caption = compose.onNodeWithTag("composer_caption_row").fetchSemanticsNode().boundsInRoot
            assertTrue("Toolbar overlaps preview", tools.bottom <= preview.top + 1f)
            assertTrue("Footer overlaps preview", preview.bottom <= footer.top + 1f)
            assertTrue("Caption is outside its footer", caption.bottom <= footer.bottom + 1f)
            compose.onNodeWithContentDescription("Enviar").assertIsDisplayed()
        }
        checkGeometry()
        compose.onNode(hasSetTextAction()).performClick().performTextInput("Comentario de prueba\nSegunda línea")
        compose.waitForIdle()
        checkGeometry()
        assertNull(sent.get())
    }

    @Test fun addingAnotherPhotoPreservesCaptionAndEditsAndClearsViewOnce() {
        val first = image()
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "novvin_qa_${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NovvinQa")
        }
        val second = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            .also(ownedGallery::add)
        context.contentResolver.openOutputStream(second).use { output ->
            File(requireNotNull(first.path)).inputStream().use { input -> input.copyTo(requireNotNull(output)) }
        }
        show(first)
        compose.onNodeWithContentDescription("Girar").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Conservar comentario")
        compose.onNodeWithContentDescription("Ver una vez").performClick()
        compose.onNodeWithContentDescription("Añadir fotos o videos").performClick()
        val galleryTag = "composer_gallery_image_${android.content.ContentUris.parseId(second)}"
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(galleryTag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(galleryTag).performClick()
        compose.onNodeWithContentDescription("Continuar").performClick()
        compose.onNodeWithText("2 seleccionados").assertExists()
        compose.onNode(hasSetTextAction()).assertTextContains("Conservar comentario")
        compose.onNodeWithContentDescription("Ver una vez").assertDoesNotExist()
        waitReady()
        compose.onNodeWithContentDescription("Enviar").performClick()
        compose.waitUntil(15_000) { sent.get() != null }
        val result = requireNotNull(sent.get())
        assertEquals(2, result.first.size)
        assertEquals("Conservar comentario", result.second)
        assertTrue(result.first.all { it.type == "image" })
        val exported = requireNotNull(context.contentResolver.openInputStream(result.first.first().uri)
            ?.use(BitmapFactory::decodeStream))
        try {
            assertEquals("Rotation was lost after adding an image", 180, exported.width)
            assertEquals(320, exported.height)
        } finally { exported.recycle() }
    }

    @Test fun singleItemFlowsDoNotOfferAddingMoreAttachments() {
        show(image(), maxItems = 1)
        compose.onNodeWithContentDescription("Añadir fotos o videos").assertDoesNotExist()
        compose.onNodeWithContentDescription("Ver una vez").assertExists()
    }
}
