package conectate.com.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceivedChatImageGalleryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun photo(): File = File(context.filesDir, "chat-media-offline/gallery-test-${UUID.randomUUID()}.media").also {
        it.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        try { it.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) } }
        finally { bitmap.recycle() }
    }
    private fun source() = "https://example.invalid/storage/v1/object/public/chat-media/gallery-${UUID.randomUUID()}.png"

    @Test fun completedIncomingImageIsPublicAndDuplicateIsNotCreated() = runBlocking {
        val account = UUID.randomUUID().toString()
        val gallery = ReceivedChatImageGallery(context, { account }, { true })
        val source = source(); val key = checkNotNull(canonicalChatMediaSourceKey(source)); val file = photo()
        var exported: Uri? = null
        try {
            gallery.register(source, UUID.randomUUID().toString(), "image", false, emptyList())
            exported = gallery.exportPending(account, key, file)
            assertNotNull(exported)
            context.contentResolver.openInputStream(exported!!).use {
                val decoded = requireNotNull(BitmapFactory.decodeStream(it))
                assertEquals(12, decoded.width); decoded.recycle()
            }
            gallery.register(source, "another-sender", "image", false, emptyList())
            assertEquals(exported, gallery.exportPending(account, key, file))
            val originalUri = exported!!
            context.contentResolver.delete(originalUri, null, null)
            exported = null
            gallery.register(source, "another-sender", "image", false, emptyList())
            assertEquals("Reopening must respect a deleted public copy", originalUri, gallery.exportPending(account, key, file))
        } finally { exported?.let { context.contentResolver.delete(it, null, null) }; file.delete() }
    }

    @Test fun ownAndViewOnceImagesNeverEnterGallery() = runBlocking {
        val account = UUID.randomUUID().toString(); val file = photo(); val source = source()
        val gallery = ReceivedChatImageGallery(context, { account }, { true })
        try {
            gallery.register(source, account, "image", false, emptyList())
            assertNull(gallery.exportPending(account, checkNotNull(canonicalChatMediaSourceKey(source)), file))
            gallery.register(source, "sender", "view_once_image", false, emptyList())
            assertNull(gallery.exportPending(account, checkNotNull(canonicalChatMediaSourceKey(source)), file))
        } finally { file.delete() }
    }

    @Test fun disabledPreferenceSkipsGallery() = runBlocking {
        val account = UUID.randomUUID().toString(); val file = photo(); val source = source()
        val gallery = ReceivedChatImageGallery(context, { account }, { false })
        try {
            gallery.register(source, "sender", "image", false, emptyList())
            assertNull(gallery.exportPending(account, checkNotNull(canonicalChatMediaSourceKey(source)), file))
        } finally { file.delete() }
    }

    @Test fun accountChangeRejectsPendingImage() = runBlocking {
        var account = UUID.randomUUID().toString(); val original = account
        val file = photo(); val source = source()
        val gallery = ReceivedChatImageGallery(context, { account }, { true })
        try {
            gallery.register(source, "sender", "image", false, emptyList())
            account = UUID.randomUUID().toString()
            assertTrue(runCatching { gallery.exportPending(original, checkNotNull(canonicalChatMediaSourceKey(source)), file) }.isFailure)
        } finally { file.delete() }
    }
}
