package conectate.com.presentation.messages.stickers

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerBitmapLoadingTest {
    @get:Rule
    val files = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun editorDecodesLargePhotoToItsPreviewSize() {
        val bitmap = decodeStickerBitmap(photoSource(4000, 3000), 1400)
        try {
            assertEquals(1400, bitmap.width)
            assertEquals(1050, bitmap.height)
            assertTrue(bitmap.isMutable)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun decoderKeepsSmallImageSize() {
        val bitmap = decodeStickerBitmap(photoSource(52, 29), 1400)
        try {
            assertEquals(52, bitmap.width)
            assertEquals(29, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun legacySamplingBoundsIntermediateDecode() {
        assertEquals(1, stickerDecodeSampleSize(1400, 1400))
        assertEquals(2, stickerDecodeSampleSize(4032, 1400))
        assertEquals(4, stickerDecodeSampleSize(8000, 1400))
        assertEquals(1 shl 30, stickerDecodeSampleSize(Int.MAX_VALUE, 1))
    }

    @Test
    fun legacyDecoderKeepsExifOrientationAfterSampling() {
        val file = writePhoto(4000, 3000)
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val bitmap = checkNotNull(decodeLegacyStickerBitmap(context, Uri.fromFile(file), 1400))
        try {
            assertEquals(1050, bitmap.width)
            assertEquals(1400, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun unreadablePhotoReturnsNoBitmap() = runBlocking {
        val uri = Uri.fromFile(files.newFile("broken.jpg").apply { writeText("broken image") })
        assertNull(loadStickerBitmapFromUri(context, uri))
        assertNull(decodeLegacyStickerBitmap(context, uri, 1400))
    }

    private fun photoSource(width: Int, height: Int): ImageDecoder.Source {
        val jpeg = writePhoto(width, height).readBytes()
        return ImageDecoder.createSource(ByteBuffer.wrap(jpeg))
    }

    private fun writePhoto(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val file = files.newFile()
        try {
            bitmap.eraseColor(Color.BLUE)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
            return file
        } finally {
            bitmap.recycle()
        }
    }
}
