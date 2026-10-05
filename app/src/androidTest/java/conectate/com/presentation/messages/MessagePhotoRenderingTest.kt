package conectate.com.presentation.messages

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pixel assertions on private local fixtures; no auth, upload, or remote recipient. */
@RunWith(AndroidJUnit4::class)
class MessagePhotoRenderingTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val ownedFiles = mutableListOf<File>()
    private val ready = AtomicBoolean(false)
    private val added = AtomicReference<List<Offset>?>(null)
    private val edit = mutableStateOf(MessagePhotoRenderState())
    private val background = AndroidColor.rgb(24, 24, 24)
    private val red = AndroidColor.rgb(220, 50, 70)
    private val green = AndroidColor.rgb(0, 200, 80)
    private val blue = AndroidColor.rgb(20, 80, 220)
    private val yellow = AndroidColor.rgb(230, 205, 30)

    @After fun cleanup() {
        ownedFiles.forEach { file ->
            if (file.canonicalFile.parentFile == context.cacheDir.canonicalFile) file.delete()
        }
    }

    private fun fixture(width: Int = 320, height: Int = 180, quadrants: Boolean = false): Uri {
        val file = File(context.cacheDir, "qa_photo_pixels_${UUID.randomUUID()}.png")
        ownedFiles += file
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(background)
            if (quadrants) {
                val canvas = Canvas(bitmap)
                val paint = Paint()
                val halfWidth = width / 2f
                val halfHeight = height / 2f
                listOf(
                    floatArrayOf(0f, 0f, halfWidth, halfHeight) to red,
                    floatArrayOf(halfWidth, 0f, width.toFloat(), halfHeight) to green,
                    floatArrayOf(0f, halfHeight, halfWidth, height.toFloat()) to blue,
                    floatArrayOf(halfWidth, halfHeight, width.toFloat(), height.toFloat()) to yellow,
                ).forEach { (rect, color) ->
                    paint.color = color
                    canvas.drawRect(rect[0], rect[1], rect[2], rect[3], paint)
                }
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return file.toUri()
    }

    private fun show(uri: Uri, state: MessagePhotoRenderState, drawing: Boolean = false) {
        edit.value = state
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(240.dp, 420.dp).background(Color.Black).testTag("photo_test_host")) {
                    MessagePhotoPreview(uri, edit.value, drawing, true,
                        onStrokeAdded = { stroke ->
                            added.set(stroke)
                            edit.value = edit.value.copy(strokes = edit.value.strokes + listOf(stroke))
                        },
                        onReadyChanged = { ready.set(it) }, modifier = Modifier.fillMaxSize())
                }
            }
        }
        awaitReady()
    }

    private fun awaitReady() {
        compose.waitUntil(10_000) { ready.get() }
        compose.waitForIdle()
    }

    private fun export(uri: Uri, state: MessagePhotoRenderState): Bitmap {
        val output = runBlocking { exportMessagePhoto(context, uri, state) }
        ownedFiles += File(requireNotNull(output.path))
        return requireNotNull(context.contentResolver.openInputStream(output)?.use(BitmapFactory::decodeStream))
    }

    private fun pixel(map: PixelMap, x: Float, y: Float): Int = map[
        (x * (map.width - 1)).roundToInt().coerceIn(0, map.width - 1),
        (y * (map.height - 1)).roundToInt().coerceIn(0, map.height - 1),
    ].toArgb()

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 28) {
        assertTrue("Red: expected $expected, got $actual", abs(AndroidColor.red(expected) - AndroidColor.red(actual)) <= tolerance)
        assertTrue("Green: expected $expected, got $actual", abs(AndroidColor.green(expected) - AndroidColor.green(actual)) <= tolerance)
        assertTrue("Blue: expected $expected, got $actual", abs(AndroidColor.blue(expected) - AndroidColor.blue(actual)) <= tolerance)
    }

    @Test fun quarterTurnMatchesFourQuadrantsInPreviewAndExportedJpeg() {
        val uri = fixture(quadrants = true)
        val state = MessagePhotoRenderState(rotateQuarterTurns = 1)
        show(uri, state)
        val preview = compose.onNodeWithTag("message_photo_preview").captureToImage().toPixelMap()
        assertEquals(180f / 320f, preview.width.toFloat() / preview.height, 0.015f)
        val exported = export(uri, state)
        try {
            assertEquals(180, exported.width)
            assertEquals(320, exported.height)
            val output = exported.asImageBitmap().toPixelMap()
            listOf(Offset(.25f, .25f) to blue, Offset(.75f, .25f) to red,
                Offset(.25f, .75f) to yellow, Offset(.75f, .75f) to green).forEach { (position, color) ->
                assertColor(color, pixel(preview, position.x, position.y))
                assertColor(color, pixel(output, position.x, position.y))
            }
        } finally { exported.recycle() }
    }

    @Test fun sourceStrokeTurnsWithPhotoAndMatchesPreviewPixels() {
        val uri = fixture(width = 1280, height = 720)
        val state = MessagePhotoRenderState(rotateQuarterTurns = 1, brushColor = red,
            strokes = listOf(listOf(Offset(.2f, .2f), Offset(.8f, .2f))))
        show(uri, state)
        val preview = compose.onNodeWithTag("message_photo_preview").captureToImage().toPixelMap()
        val exported = export(uri, state)
        try {
            val output = exported.asImageBitmap().toPixelMap()
            assertColor(red, pixel(preview, .8f, .5f))
            assertColor(red, pixel(output, .8f, .5f))
            assertColor(background, pixel(preview, .2f, .5f))
            assertColor(background, pixel(output, .2f, .5f))
        } finally { exported.recycle() }
    }

    @Test fun letterboxGestureCannotPaintPhotoAndRealPhotoGestureExportsAtSameHeight() {
        val uri = fixture(width = 1280, height = 720)
        show(uri, MessagePhotoRenderState(brushColor = red), drawing = true)
        val preview = compose.onNodeWithTag("message_photo_preview").captureToImage()
        assertEquals(320f / 180f, preview.width.toFloat() / preview.height, .02f)
        compose.onNodeWithTag("photo_test_host").performTouchInput {
            swipe(Offset(width * .25f, 8f), Offset(width * .75f, 8f), 300)
        }
        compose.waitForIdle()
        assertNull("The black letterbox was interpreted as photo pixels", added.get())
        compose.onNodeWithTag("message_photo_preview").performTouchInput {
            swipe(Offset(width * .25f, height * .2f), Offset(width * .75f, height * .2f), 300)
        }
        compose.waitUntil(10_000) { added.get() != null && ready.get() }
        compose.waitForIdle()
        added.get()!!.forEach { assertEquals(.2f, it.y, .025f) }
        val drawn = compose.onNodeWithTag("message_photo_preview").captureToImage().toPixelMap()
        val exported = export(uri, edit.value)
        try {
            val output = exported.asImageBitmap().toPixelMap()
            assertColor(red, pixel(drawn, .5f, .2f))
            assertColor(red, pixel(output, .5f, .2f))
            assertColor(background, pixel(output, .5f, .42f))
        } finally { exported.recycle() }
    }

    @Test fun rotatedGestureIsStoredInSourceCoordinates() {
        val uri = fixture(width = 1280, height = 720)
        show(uri, MessagePhotoRenderState(rotateQuarterTurns = 1, brushColor = red), drawing = true)
        compose.onNodeWithTag("message_photo_preview").performTouchInput {
            swipe(Offset(width * .8f, height * .25f), Offset(width * .8f, height * .75f), 300)
        }
        compose.waitUntil(10_000) { added.get() != null && ready.get() }
        compose.waitForIdle()
        val stroke = requireNotNull(added.get())
        assertTrue(stroke.size > 1)
        stroke.forEach { assertEquals(.2f, it.y, .025f) }
        assertTrue(stroke.last().x > stroke.first().x)
        val exported = export(uri, edit.value)
        try { assertColor(red, pixel(exported.asImageBitmap().toPixelMap(), .8f, .5f)) }
        finally { exported.recycle() }
    }

    private data class InkBounds(val centerX: Float, val centerY: Float, val width: Float, val height: Float)

    private fun whiteInk(map: PixelMap): InkBounds {
        var left = map.width
        var top = map.height
        var right = -1
        var bottom = -1
        for (y in 0 until map.height) for (x in 0 until map.width) {
            val color = map[x, y].toArgb()
            if (AndroidColor.red(color) > 210 && AndroidColor.green(color) > 210 && AndroidColor.blue(color) > 210) {
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("No white text pixels were rendered", right >= left && bottom >= top)
        return InkBounds((left + right) / (2f * map.width), (top + bottom) / (2f * map.height),
            (right - left + 1f) / map.width, (bottom - top + 1f) / map.height)
    }

    @Test fun textPositionAndSizeMatchAcrossPreviewAndFullResolutionExport() {
        val uri = fixture(width = 1600, height = 900)
        val state = MessagePhotoRenderState(overlayText = "QA")
        show(uri, state)
        val preview = whiteInk(compose.onNodeWithTag("message_photo_preview").captureToImage().toPixelMap())
        val exported = export(uri, state)
        try {
            assertEquals(1600, exported.width)
            val output = whiteInk(exported.asImageBitmap().toPixelMap())
            assertEquals(.5f, preview.centerX, .03f)
            assertEquals(.5f, preview.centerY, .04f)
            assertEquals(output.centerX, preview.centerX, .02f)
            assertEquals(output.centerY, preview.centerY, .02f)
            assertEquals(output.width, preview.width, .02f)
            assertEquals(output.height, preview.height, .02f)
        } finally { exported.recycle() }
    }

    @Test fun inverseCoordinatesRoundTripAllRotationsAndRejectOutsidePoints() {
        val source = Offset(.27f, .63f)
        for (turns in -4..7) {
            val restored = requireNotNull(messagePhotoSourcePoint(messagePhotoDisplayPoint(source, turns), turns))
            assertEquals(source.x, restored.x, .0001f)
            assertEquals(source.y, restored.y, .0001f)
        }
        assertNull(messagePhotoSourcePoint(Offset(-.1f, .5f), 0))
        assertNull(messagePhotoSourcePoint(Offset(.5f, 1.1f), 1))
        assertNull(messagePhotoSourcePoint(Offset(Float.NaN, .5f), 0))
    }

    @Test fun invalidSourceShowsErrorAndExportNeverReturnsOriginal() {
        val missing = File(context.cacheDir, "qa_missing_photo_${UUID.randomUUID()}.png").toUri()
        compose.setContent {
            MaterialTheme {
                MessagePhotoPreview(missing, MessagePhotoRenderState(rotateQuarterTurns = 1), false, true,
                    onStrokeAdded = {}, onReadyChanged = { ready.set(it) }, modifier = Modifier.size(240.dp, 420.dp))
            }
        }
        compose.waitUntil(10_000) {
            compose.onNodeWithText("No se pudo mostrar la foto editada.").runCatching { fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithText("Reintentar").assertIsDisplayed()
        assertFalse(ready.get())
        var rejected = false
        try { runBlocking { exportMessagePhoto(context, missing, MessagePhotoRenderState(rotateQuarterTurns = 1)) } }
        catch (_: IOException) { rejected = true }
        assertTrue("Invalid editing returned an original URI", rejected)
    }
}
