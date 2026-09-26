package conectate.com.data.local

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class PostDraftStoreTest {
    @Test fun interruptedReplacementRestoresLastCompleteDraft() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = "recover-${System.nanoTime()}"
        val key = MessageDigest.getInstance("SHA-256")
            .digest(owner.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(40)
        val root = File(context.filesDir, "post-drafts")
        val target = File(root, key)
        val backup = File(root, "$key.backup")
        try {
            PostDraftStore.save(context, owner, "Texto conservado", emptyList(), "public", false)
                .getOrThrow()
            assertEquals(true, target.renameTo(backup))
            assertEquals(true, target.mkdirs())
            File(target, "image-0.jpg").writeText("incomplete")
            val restored = PostDraftStore.load(context, owner).getOrThrow()
            assertEquals("Texto conservado", restored?.content)
            assertFalse(backup.exists())
        } finally {
            PostDraftStore.delete(context, owner)
        }
    }

    @Test fun draftKeepsPrivateImageCopyAfterSourceDisappears() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = "draft-owner-${System.nanoTime()}"
        val other = "draft-other-${System.nanoTime()}"
        val source = File(context.cacheDir, "post-draft-source-${System.nanoTime()}.jpg")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        source.writeBytes(bytes)
        try {
            val saved = PostDraftStore.save(
                context, owner, "Una idea para después", listOf(Uri.fromFile(source)),
                "private", true,
            ).getOrThrow()
            assertEquals(1, saved.mediaUris.size)
            assertFalse(saved.mediaUris.first() == Uri.fromFile(source))
            source.delete()
            val restored = PostDraftStore.load(context, owner).getOrThrow()
            assertNotNull(restored)
            assertEquals("Una idea para después", restored!!.content)
            assertEquals("private", restored.visibility)
            assertEquals(true, restored.isSensitive)
            assertEquals(bytes.toList(), context.contentResolver.openInputStream(restored.mediaUris.first())!!
                .use { it.readBytes().toList() })
            assertNull(PostDraftStore.load(context, other).getOrThrow())
            PostDraftStore.delete(context, owner).getOrThrow()
            assertNull(PostDraftStore.load(context, owner).getOrThrow())
        } finally {
            source.delete()
            PostDraftStore.delete(context, owner)
        }
    }
}
