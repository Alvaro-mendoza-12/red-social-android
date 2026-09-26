package conectate.com.data.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CommunityMusicArtworkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `valid embedded picture becomes bounded JPEG cover`() {
        val bitmap = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val file = prepareCommunityMusicArtwork(context, bytes)
        assertNotNull(file)
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file!!.absolutePath, bounds)
            assertEquals("image/jpeg", bounds.outMimeType)
            assertTrue(bounds.outWidth in 1..1024)
            assertTrue(bounds.outHeight in 1..1024)
            assertTrue(file.canonicalPath.startsWith(context.cacheDir.canonicalPath + java.io.File.separator))
        } finally { file?.delete() }
    }

    @Test fun `absent malformed and oversized artwork do not produce an upload`() {
        assertNull(prepareCommunityMusicArtwork(context, byteArrayOf()))
        assertNull(prepareCommunityMusicArtwork(context, "not an image".toByteArray()))
        assertNull(prepareCommunityMusicArtwork(context, ByteArray(10 * 1024 * 1024 + 1)))
    }

    @Test fun `embedded cover file URI is prepared without content provider metadata`() {
        val source = File.createTempFile("embedded-cover-", ".jpg", context.cacheDir).apply { writeBytes(pngBytes()) }
        var upload: File? = null
        try {
            val uri = Uri.fromFile(source)
            assertEquals(source.name, resolveCommunityDisplayName(context, uri))
            upload = prepareCommunityMusicCoverUpload(context, uri)
            assertNotEquals(source.canonicalPath, upload.canonicalPath)
            assertJpeg(upload)
            source.delete()
            assertTrue("Upload owns its copy independently of the dialog", upload.exists())
        } finally { source.delete(); upload?.delete() }
    }

    @Test fun `album art content URI without MIME or filename is decoded from its bytes`() {
        val uri = Uri.parse("content://media/external/audio/albumart/987654")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(pngBytes()))
        val upload = prepareCommunityMusicCoverUpload(context, uri)
        try { assertJpeg(upload) } finally { upload.delete() }
    }

    @Test fun `mislabelled cover still fails image validation before upload`() {
        val source = File.createTempFile("invalid-cover-", ".jpg", context.cacheDir).apply { writeText("not an image") }
        try {
            assertThrows(IllegalStateException::class.java) { prepareCommunityMusicCoverUpload(context, Uri.fromFile(source)) }
        } finally { source.delete() }
    }

    @Test fun `invalid artwork prevents the upload stage from starting`() = runBlocking {
        val source = File.createTempFile("invalid-cover-", ".jpg", context.cacheDir).apply { writeText("not an image") }
        var uploadStarted = false
        try {
            val result = runCatching {
                withPreparedCommunityCoverUpload(context, Uri.fromFile(source)) {
                    uploadStarted = true
                }
            }
            assertTrue(result.isFailure)
            assertFalse(uploadStarted)
        } finally { source.delete() }
    }

    @Test fun `prepared JPEG survives during upload and is removed when upload cancels`() = runBlocking {
        val source = File.createTempFile("cover-source-", ".png", context.cacheDir).apply { writeBytes(pngBytes()) }
        var uploadFile: File? = null
        try {
            val failure = runCatching {
                withPreparedCommunityCoverUpload(context, Uri.fromFile(source)) { cover ->
                    uploadFile = cover
                    assertJpeg(requireNotNull(cover))
                    assertTrue(source.exists())
                    throw CancellationException("test cancellation")
                }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertNotNull(uploadFile)
            assertFalse(requireNotNull(uploadFile).exists())
            assertTrue("The source belongs to the picker", source.exists())
        } finally { source.delete(); uploadFile?.delete() }
    }

    private fun pngBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
    }

    private fun assertJpeg(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        assertEquals("image/jpeg", bounds.outMimeType)
        assertTrue(bounds.outWidth in 1..1024)
        assertTrue(bounds.outHeight in 1..1024)
    }
}
