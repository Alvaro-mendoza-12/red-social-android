package conectate.com.presentation.videos

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import conectate.com.data.model.VideoChapter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoUploadDraftStoreTest {
    @Test
    fun savedDraftSurvivesStoreRecreationAndRemainsAccountPrivate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstAccount = "draft-test-${UUID.randomUUID()}"
        val secondAccount = "draft-test-${UUID.randomUUID()}"
        val id = UUID.randomUUID().toString()
        val source = File.createTempFile("video-draft-source-", ".mp4", context.cacheDir)
        val cover = File.createTempFile("video-draft-cover-", ".jpg", context.cacheDir)
        source.writeBytes(byteArrayOf(0, 1, 2, 3))
        cover.writeBytes(byteArrayOf(4, 5, 6))
        try {
            val saved = VideoUploadDraftStore(context).save(
                firstAccount,
                VideoUploadDraft(id = id, title = "Continuar más tarde", soundId = "sound-1",
                    allowClipSharing = false,
                    chapters = listOf(VideoChapter(0L, "Inicio"))),
                Uri.fromFile(source), Uri.fromFile(cover),
            )
            assertTrue(saved.hasVideo)
            assertTrue(saved.hasThumbnail)
            source.delete()
            cover.delete()

            val restoredStore = VideoUploadDraftStore(context)
            assertEquals("Continuar más tarde", restoredStore.load(firstAccount, id)?.title)
            assertEquals("sound-1", restoredStore.list(firstAccount).firstOrNull { it.id == id }?.soundId)
            assertEquals(listOf(VideoChapter(0L, "Inicio")), restoredStore.load(firstAccount, id)?.chapters)
            assertFalse(restoredStore.load(firstAccount, id)?.allowClipSharing ?: true)
            assertEquals(listOf<Byte>(0, 1, 2, 3),
                restoredStore.videoUri(firstAccount, id)?.path?.let { File(it).readBytes().toList() })
            assertNotNull(restoredStore.thumbnailUri(firstAccount, id))
            assertNull(restoredStore.load(secondAccount, id))
            assertTrue(restoredStore.list(secondAccount).isEmpty())

            assertTrue(restoredStore.delete(firstAccount, id))
            assertNull(restoredStore.load(firstAccount, id))
            assertFalse(restoredStore.list(firstAccount).any { it.id == id })
        } finally {
            source.delete()
            cover.delete()
            VideoUploadDraftStore(context).delete(firstAccount, id)
        }
    }
}
