package conectate.com.presentation.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real JPEG orientation, rendered preview, preset and delivered crop agree. */
class ImageCropOrientationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val originals = mutableListOf<File>()
    private val outputs = mutableListOf<Uri>()

    private fun photo(orientation: Int): Uri {
        val file = File.createTempFile("crop-orientation-", ".jpg", context.cacheDir)
        originals += file
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
            for (x in 0 until 100) for (y in 0 until 100) setPixel(x, y, android.graphics.Color.RED)
        }
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) } }
        finally { bitmap.recycle() }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return Uri.fromFile(file)
    }

    private fun show(uri: Uri): AtomicReference<Uri?> {
        val delivered = AtomicReference<Uri?>()
        compose.setContent {
            MaterialTheme {
                SimpleImageCropDialog(uri, onDismiss = {}, onCropped = {
                    delivered.set(it)
                    if (it != uri) outputs += it
                })
            }
        }
        // Wait for actual decoded pixels, not just metadata or a placeholder.
        compose.waitUntil(10_000) {
            val pixels = runCatching {
                compose.onNodeWithContentDescription("Vista previa del recorte").captureToImage().toPixelMap()
            }.getOrNull()
            pixels?.let { val center = it[it.width / 2, it.height / 2]
                center.red > .8f && center.blue > .8f && center.green < .2f } == true
        }
        return delivered
    }

    @Test fun originalPortraitPreviewKeepsItsCameraOrientationAndOriginalFile() {
        val uri = photo(ExifInterface.ORIENTATION_ROTATE_90)
        val before = File(uri.path!!).readBytes()
        val delivered = show(uri)
        val bounds = compose.onNodeWithContentDescription("Vista previa del recorte").fetchSemanticsNode().boundsInRoot
        assertEquals("Portrait pixels were stretched into a horizontal frame", .75f, bounds.width / bounds.height, .01f)
        compose.onNodeWithText("Usar").performClick()
        compose.waitUntil(10_000) { delivered.get() != null }
        assertEquals(uri, delivered.get())
        assertArrayEquals(before, File(uri.path!!).readBytes())
    }

    @Test fun portraitPresetIsAlsoFourByFiveInTheDeliveredJpeg() {
        val delivered = show(photo(ExifInterface.ORIENTATION_ROTATE_90))
        compose.onNodeWithText("4:5").performClick()
        compose.onNodeWithText("Usar").performClick()
        compose.waitUntil(10_000) { delivered.get() != null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(delivered.get()!!).use { BitmapFactory.decodeStream(it, null, bounds) }
        assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
        assertEquals("The sent crop does not match the selected preset", .8f, bounds.outWidth.toFloat() / bounds.outHeight, .005f)
    }

    @Test fun transposedCameraPhotoHasAPortraitPreview() {
        show(photo(ExifInterface.ORIENTATION_TRANSVERSE))
        val bounds = compose.onNodeWithContentDescription("Vista previa del recorte").fetchSemanticsNode().boundsInRoot
        assertEquals(.75f, bounds.width / bounds.height, .01f)
    }

    @Test @SdkSuppress(minSdkVersion = 28)
    fun legacyExportMatchesAndroidDecoderForEveryExifRotationAndReflection() {
        for (orientation in 1..8) {
            val uri = photo(orientation)
            val expected = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            val legacy = decodeLegacyImageCropBitmap(context, uri)
            try {
                assertEquals("EXIF $orientation width", expected.width, legacy.width)
                assertEquals("EXIF $orientation height", expected.height, legacy.height)
                for (x in listOf(.1f, .9f)) for (y in listOf(.1f, .9f)) {
                    val column = (expected.width * x).toInt()
                    val row = (expected.height * y).toInt()
                    assertEquals("EXIF $orientation pixel $x,$y", expected.getPixel(column, row), legacy.getPixel(column, row))
                }
            } finally { expected.recycle(); legacy.recycle() }
        }
    }

    @After fun removeOnlyThisTestsImages() {
        outputs.forEach { if (it.authority == "${context.packageName}.provider") context.contentResolver.delete(it, null, null) }
        originals.forEach { it.delete() }
    }
}
