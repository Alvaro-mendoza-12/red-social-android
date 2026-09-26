package conectate.com.data.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the Android decoder and file resolver without provider MIME or display-name hints. */
@RunWith(AndroidJUnit4::class)
@SmallTest
class CommunityMusicCoverTest {
    @Test
    fun localPngWithoutExtensionBecomesBoundedJpegAndOriginalIsPreserved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("music-cover-original-", ".unknown", context.cacheDir)
        var upload: File? = null
        try {
            val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.rgb(42, 118, 203))
                source.outputStream().use { output ->
                    assertTrue("PNG fixture could not be encoded", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally { bitmap.recycle() }
            val originalBytes = source.readBytes()
            assertEquals(0x89, originalBytes[0].toInt() and 0xff)
            val sourceUri = Uri.fromFile(source)
            assertNull("Fixture must not rely on a provider's MIME hint", context.contentResolver.getType(sourceUri))

            val prepared = prepareCommunityMusicCoverUpload(context, sourceUri).also { upload = it }
            assertNotEquals(source.canonicalPath, prepared.canonicalPath)
            assertTrue("Prepared upload must use a JPEG suffix", prepared.name.endsWith(".jpg"))
            val bytes = prepared.readBytes()
            assertTrue(bytes.size > 4)
            assertEquals(0xff, bytes[0].toInt() and 0xff)
            assertEquals(0xd8, bytes[1].toInt() and 0xff)
            val decoded = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decoded)
            assertEquals("image/jpeg", decoded.outMimeType)
            assertEquals(1024, decoded.outWidth)
            assertEquals(512, decoded.outHeight)
            assertTrue(source.isFile)
            assertArrayEquals("Preparing artwork must not replace original bytes", originalBytes, source.readBytes())

            assertTrue("The caller must be able to clean up its temporary upload", prepared.delete())
            assertFalse(prepared.exists())
            assertTrue("Cleaning the temporary cover must preserve the original", source.isFile)
            assertArrayEquals(originalBytes, source.readBytes())
        } finally {
            upload?.delete()
            source.delete()
        }
    }
}
