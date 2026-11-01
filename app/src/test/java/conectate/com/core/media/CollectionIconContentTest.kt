package conectate.com.core.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionIconContentTest {
    private val application: Application get() = RuntimeEnvironment.getApplication()
    private fun image(width: Int, height: Int, alpha: Boolean = false, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(if (alpha) Color.argb(80, 0, 0, 255) else Color.BLUE)
        bitmap.setHasAlpha(alpha)
        return try { ByteArrayOutputStream().also { assertTrue(bitmap.compress(format, 88, it)) }.toByteArray() }
        finally { bitmap.recycle() }
    }
    private fun bounds(bytes: ByteArray) = BitmapFactory.Options().apply {
        inJustDecodeBounds = true; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
    }

    @Test fun aPngIconUsesItsActualMimeAndPassesTheRealUploadPolicy() {
        val source = image(32, 24, alpha = true)
        val icon = normalizeCollectionIcon(source)
        assertEquals("image/png", icon.contentType)
        assertEquals("png", icon.extension)
        assertTrue(MediaUploadGuard().validate("groups", "icon.${icon.extension}", icon.bytes, icon.contentType).allowed)
        assertFalse(MediaUploadGuard().validate("groups", "icon.jpg", source, "image/jpeg").allowed)
    }

    @Test fun largerPhotosAreDecodedAndUploadedAsBoundedIcons() {
        val icon = normalizeCollectionIcon(image(2049, 1025))
        val size = bounds(icon.bytes)
        assertTrue(size.outWidth in 1..1024)
        assertTrue(size.outHeight in 1..1024)
        assertTrue(icon.bytes.size < 1_000_000)
        assertTrue(MediaUploadGuard().validate("communities", "icon.${icon.extension}", icon.bytes, icon.contentType).allowed)
    }

    @Test fun transparencySurvivesNormalizingALargeIcon() {
        val icon = normalizeCollectionIcon(image(2049, 1025, alpha = true))
        val bitmap = BitmapFactory.decodeByteArray(icon.bytes, 0, icon.bytes.size)
        try { assertTrue(bitmap.hasAlpha()); assertTrue(Color.alpha(bitmap.getPixel(0, 0)) < 255) }
        finally { bitmap.recycle() }
        assertEquals("image/png", icon.contentType)
    }

    @Test fun anExifRotatedPhotoKeepsItsDisplayedOrientationAfterEncoding() {
        val file = File.createTempFile("qa-collection-icon-", ".jpg", application.cacheDir)
        try {
            file.writeBytes(image(40, 20, format = Bitmap.CompressFormat.JPEG))
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val icon = normalizeCollectionIcon(file.readBytes())
            val size = bounds(icon.bytes)
            assertEquals(20, size.outWidth)
            assertEquals(40, size.outHeight)
        } finally { file.delete() }
    }

    @Test fun webpIconsRetainTheirValidContentType() {
        @Suppress("DEPRECATION") val source = image(24, 16, format = Bitmap.CompressFormat.WEBP)
        val icon = normalizeCollectionIcon(source)
        assertEquals("image/webp", icon.contentType)
        assertEquals("webp", icon.extension)
        assertTrue(MediaUploadGuard().validate("groups", "icon.webp", icon.bytes, icon.contentType).allowed)
    }

    @Test fun malformedOrEmptyContentNeverBecomesAnUpload() {
        for (bytes in listOf(byteArrayOf(), "not an image".toByteArray()))
            assertTrue(runCatching { normalizeCollectionIcon(bytes) }.isFailure)
    }
}
