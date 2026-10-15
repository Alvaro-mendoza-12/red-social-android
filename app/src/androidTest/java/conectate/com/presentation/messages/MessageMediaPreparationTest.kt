package conectate.com.presentation.messages

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Private owned images only; the callback is never connected to another account. */
@RunWith(AndroidJUnit4::class)
class MessageMediaPreparationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownedFiles = mutableListOf<File>()
    private val sent = AtomicReference<Pair<List<OutgoingMessageMedia>, String>?>()

    @After fun cleanup() {
        sent.get()?.first?.mapNotNull { it.uri.path?.let(::File) }?.forEach { file ->
            if (file.canonicalFile.parentFile == context.cacheDir.canonicalFile && file.name.startsWith("message_media_")) {
                file.delete()
            }
        }
        ownedFiles.forEach { it.delete() }
    }

    private fun ownImage(): Uri {
        val file = File(context.cacheDir, "qa_composer_${UUID.randomUUID()}.png").also(ownedFiles::add)
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(30, 190, 100))
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file.toUri()
    }

    private fun missingImage(): Uri = File(context.cacheDir, "qa_missing_${UUID.randomUUID()}.jpg").toUri()

    private fun show(uris: List<Uri>, isSending: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                MessageMediaComposer(initialSelectedUris = uris.map { it to "image" }, initialStep = "editor",
                    isSending = isSending, onDismiss = {}, onSend = { items, caption -> sent.set(items to caption) })
            }
        }
        if (!isSending) compose.onNode(hasSetTextAction()).performTextInput("Comentario QA conservado")
    }

    private fun assertRecoverableFailure(activePhotoWasInvalidated: Boolean = false) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("No se pudo preparar la foto o el video", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertNull("Failed editing must never send the original", sent.get())
        compose.onNode(hasSetTextAction()).assertTextContains("Comentario QA conservado")
        if (activePhotoWasInvalidated) compose.onNodeWithContentDescription("Enviar").assertIsNotEnabled()
        else compose.onNodeWithContentDescription("Enviar").assertIsEnabled()
        compose.onNodeWithContentDescription("Girar").assertIsDisplayed()
    }

    private fun waitForRenderedPhoto() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription("Enviar").fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false
        }
    }

    @Test fun lostSnapshotKeepsEditorAndExplicitRetryRendersBeforeSending() {
        val original = ownImage()
        val previousSources = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("message_source_") }.map { it.name }.toSet()
        show(listOf(original))
        compose.onNodeWithContentDescription("Girar").performClick()
        waitForRenderedPhoto()
        val snapshot = context.cacheDir.listFiles().orEmpty().single { it.name.startsWith("message_source_") && it.name !in previousSources }
        assertTrue(snapshot.delete())
        compose.onNodeWithContentDescription("Enviar").performClick()
        assertRecoverableFailure(activePhotoWasInvalidated = true)
        compose.onNodeWithText("Reintentar").performClick()
        waitForRenderedPhoto()
        assertNull(sent.get())
        compose.onNodeWithContentDescription("Enviar").performClick()
        compose.waitUntil(15_000) { sent.get() != null }
        assertNotEquals(original, sent.get()!!.first.single().uri)
        assertEquals("Comentario QA conservado", sent.get()!!.second)
    }

    @Test fun replacingTheOriginalAfterPreviewKeepsTheRepresentedBytesForSending() {
        val original = ownImage()
        show(listOf(original))
        waitForRenderedPhoto()
        val replacement = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        replacement.eraseColor(AndroidColor.BLUE)
        File(requireNotNull(original.path)).outputStream().use { assertTrue(replacement.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        replacement.recycle()
        compose.onNodeWithContentDescription("Enviar").performClick()
        compose.waitUntil(15_000) { sent.get() != null }
        val output = requireNotNull(context.contentResolver.openInputStream(sent.get()!!.first.single().uri)?.use(BitmapFactory::decodeStream))
        try {
            val actual = output.getPixel(output.width / 2, output.height / 2)
            assertTrue("Sending re-read a changed original rather than the visible snapshot", AndroidColor.green(actual) > 150 && AndroidColor.blue(actual) < 130)
        } finally { output.recycle() }
    }

    @Test fun validRotationExportsDecodedJpegAndCaptionOnlyAfterConfirmation() {
        val original = ownImage()
        show(listOf(original))
        compose.onNodeWithContentDescription("Girar").performClick()
        waitForRenderedPhoto()
        assertNull(sent.get())
        compose.onNodeWithContentDescription("Enviar").performClick()
        compose.waitUntil(10_000) { sent.get() != null }
        val (items, caption) = requireNotNull(sent.get())
        assertEquals(1, items.size)
        assertNotEquals(original, items.single().uri)
        assertEquals("Comentario QA conservado", caption)
        val exported = context.contentResolver.openInputStream(items.single().uri)?.use(BitmapFactory::decodeStream)
        assertNotNull(exported)
        assertEquals(180, exported!!.width)
        assertEquals(320, exported.height)
        exported.recycle()
        assertTrue(File(requireNotNull(original.path)).isFile)
    }

    @Test fun aFailedSecondImageAbortsTheBatchAndRemovesPreparedOutputs() {
        val original = ownImage()
        val before = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("message_media_") }.map { it.name }.toSet()
        show(listOf(original, missingImage()))
        compose.onNodeWithContentDescription("Girar").performClick()
        waitForRenderedPhoto()
        compose.onNodeWithContentDescription("Enviar").performClick()
        assertRecoverableFailure()
        val after = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("message_media_") }.map { it.name }.toSet()
        assertEquals("An aborted batch left a prepared image behind", before, after)
        assertTrue(File(requireNotNull(original.path)).isFile)
    }

    @Test fun sendingDisablesEditingSelectionAndViewOnceControls() {
        show(listOf(ownImage()), isSending = true)
        listOf("Girar", "Recortar libre", "Dibujar", "Emoji", "Ver una vez", "Galería", "Enviar", "Cerrar").forEach {
            compose.onNodeWithContentDescription(it).assertIsNotEnabled()
        }
        // A disabled text field intentionally removes its SetText action.
        compose.onNodeWithText("Añade un comentario...").assertIsNotEnabled()
        assertNull(sent.get())
    }

    @Test fun confirmedViewOnceIsRetainedInThePreparedImage() {
        show(listOf(ownImage()))
        compose.onNodeWithContentDescription("Ver una vez").performClick()
        compose.onNodeWithContentDescription("Girar").performClick()
        waitForRenderedPhoto()
        compose.onNodeWithContentDescription("Enviar").performClick()
        compose.waitUntil(10_000) { sent.get() != null }
        assertEquals("view_once_image", sent.get()!!.first.single().type)
    }

    @Test fun croppingTheVisibleRotationAndStrokeDoesNotApplyThemTwice() {
        val file = File(context.cacheDir, "qa_composer_${UUID.randomUUID()}.png").also(ownedFiles::add)
        val fixture = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fixture)
        val paint = Paint()
        listOf(
            floatArrayOf(0f, 0f, 160f, 90f) to AndroidColor.RED,
            floatArrayOf(160f, 0f, 320f, 90f) to AndroidColor.GREEN,
            floatArrayOf(0f, 90f, 160f, 180f) to AndroidColor.BLUE,
            floatArrayOf(160f, 90f, 320f, 180f) to AndroidColor.YELLOW,
        ).forEach { (rect, color) -> paint.color = color; canvas.drawRect(rect[0], rect[1], rect[2], rect[3], paint) }
        file.outputStream().use { assertTrue(fixture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        fixture.recycle()
        show(listOf(file.toUri()))
        compose.onNodeWithContentDescription("Girar").performClick()
        waitForRenderedPhoto()
        compose.onNodeWithContentDescription("Dibujar").performClick()
        compose.onNodeWithTag("message_photo_preview").performTouchInput {
            swipe(Offset(width * .15f, height * .2f), Offset(width * .15f, height * .8f), 400)
        }
        waitForRenderedPhoto()
        compose.onNodeWithContentDescription("Recortar libre").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Recortar foto").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Usar").fetchSemanticsNodes().singleOrNull()
                ?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) == false
        }
        compose.onNodeWithText("1:1").performClick()
        compose.onNodeWithText("Usar").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Recortar foto").fetchSemanticsNodes().isEmpty() }
        waitForRenderedPhoto()
        compose.onNodeWithContentDescription("Enviar").performClick()
        compose.waitUntil(15_000) { sent.get() != null }
        val photo = requireNotNull(context.contentResolver.openInputStream(sent.get()!!.first.single().uri)?.use(BitmapFactory::decodeStream))
        try {
            assertEquals(180, photo.width); assertEquals(180, photo.height)
            listOf(Offset(.3f, .25f) to AndroidColor.BLUE, Offset(.75f, .25f) to AndroidColor.RED,
                Offset(.3f, .75f) to AndroidColor.YELLOW, Offset(.75f, .75f) to AndroidColor.GREEN).forEach { (point, color) ->
                val actual = photo.getPixel((point.x * photo.width).toInt(), (point.y * photo.height).toInt())
                assertTrue(kotlin.math.abs(AndroidColor.red(color) - AndroidColor.red(actual)) < 35)
                assertTrue(kotlin.math.abs(AndroidColor.green(color) - AndroidColor.green(actual)) < 35)
                assertTrue(kotlin.math.abs(AndroidColor.blue(color) - AndroidColor.blue(actual)) < 35)
            }
            val stroke = photo.getPixel((photo.width * .15f).toInt(), photo.height / 2)
            assertTrue("The visible stroke disappeared or moved after crop", AndroidColor.red(stroke) > 180 &&
                AndroidColor.green(stroke) > 180 && AndroidColor.blue(stroke) > 180)
        } finally { photo.recycle() }
    }
}
