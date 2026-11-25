package conectate.com.data.repository

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import conectate.com.core.media.MediaUploadGuard
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MomentImagePayloadTest {
    @Test
    fun supportedPhotosKeepTheirOriginalBytesAndActualType() {
        val source = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.BLUE)
            val formats = listOf(
                Triple(Bitmap.CompressFormat.JPEG, "image/jpeg", "jpg"),
                Triple(Bitmap.CompressFormat.PNG, "image/png", "png"),
                Triple(Bitmap.CompressFormat.WEBP_LOSSLESS, "image/webp", "webp"),
            )
            for ((format, mime, extension) in formats) {
                val output = ByteArrayOutputStream()
                assertTrue(source.compress(format, 92, output))
                val bytes = output.toByteArray()
                val photo = prepareMomentImage(bytes).getOrThrow()
                assertSame(bytes, photo.bytes)
                assertEquals(mime, photo.contentType)
                assertEquals(extension, photo.extension)
            }
        } finally {
            source.recycle()
        }
    }

    @Test
    fun largePhotosInAlternateFormatsAreBoundedAndUploadAsJpeg() {
        val photo = prepareMomentImage(bitmapFile(4096, 640)).getOrThrow()
        val dimensions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size, this)
        }
        assertEquals("image/jpeg", dimensions.outMimeType)
        assertEquals(2048, dimensions.outWidth)
        assertEquals(320, dimensions.outHeight)
        assertEquals("jpg", photo.extension)
        assertTrue(MediaUploadGuard().validate("moment-media", "moment.jpg", photo.bytes, photo.contentType).allowed)
    }

    @Test
    fun unreadableBytesDoNotBecomeAReadyUpload() {
        assertTrue(prepareMomentImage(byteArrayOf()).isFailure)
        assertTrue(prepareMomentImage("archivo que no contiene una imagen".encodeToByteArray()).isFailure)
    }

    private fun bitmapFile(width: Int, height: Int): ByteArray {
        val rowSize = (width * 3 + 3) and -4
        val pixelBytes = rowSize * height
        val headerSize = 54
        return ByteBuffer.allocate(headerSize + pixelBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put('B'.code.toByte())
            .put('M'.code.toByte())
            .putInt(headerSize + pixelBytes)
            .putInt(0)
            .putInt(headerSize)
            .putInt(40)
            .putInt(width)
            .putInt(height)
            .putShort(1)
            .putShort(24)
            .putInt(0)
            .putInt(pixelBytes)
            .array()
    }
}
