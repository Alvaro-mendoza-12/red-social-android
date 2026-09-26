package conectate.com.core.media

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatMediaExportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `export keeps known media extensions without copying remote names or query strings`() {
        assertEquals("Novvin_12.webp", chatMediaExportFilename("https://cdn.test/private.webp?token=secret", "image", 12L))
        assertEquals("Novvin_12.gif", chatMediaExportFilename("chat-media/object", "gif", 12L))
        assertEquals("Novvin_12.webm", chatMediaExportFilename("chat-media/clip.WEBM#fragment", "video", 12L))
        assertEquals("Novvin_12.mp4", chatMediaExportFilename("../../dangerous.exe", "video", 12L))
    }

    private suspend fun localMedia(account: String): File {
        val cache = ChatMediaOfflineCache(
            rootDirectory = File(temporary.root, "chat-media-offline"),
            accountIdProvider = { account },
            automaticMediaEnabled = { true },
            availableBytes = { Long.MAX_VALUE / 4L }
        )
        return cache.storeForTest("chat-media/photo.jpg", "image/jpeg", 3L,
            ByteArrayInputStream(byteArrayOf(1, 2, 3)), retain = true).getOrThrow()
    }

    @Test fun `complete offline file can be exported without internet`() = runBlocking {
        val file = localMedia("viewer")
        assertEquals(file.canonicalFile, resolveOfflineChatExportFile(temporary.root, file.toURI().toString(), " Viewer "))
    }

    @Test fun `another account cannot export a cached private file`() = runBlocking {
        val file = localMedia("first")
        assertTrue(runCatching { resolveOfflineChatExportFile(temporary.root, file.toURI().toString(), "second") }.isFailure)
        assertTrue(runCatching { resolveOfflineChatExportFile(temporary.root, file.toURI().toString(), null) }.isFailure)
    }

    @Test fun `export rejects arbitrary app files and remote URLs`() {
        val other = temporary.newFile("private.media").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(runCatching { resolveOfflineChatExportFile(temporary.root, other.toURI().toString(), "viewer") }.isFailure)
        assertTrue(runCatching { resolveOfflineChatExportFile(temporary.root, "https://example.test/image.jpg", "viewer") }.isFailure)
    }

    @Test fun `cleared and partial files are not exportable`() = runBlocking {
        val file = localMedia("viewer")
        val partial = File(file.parentFile, "interrupted.part").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(runCatching { resolveOfflineChatExportFile(temporary.root, partial.toURI().toString(), "viewer") }.isFailure)
        file.delete()
        assertTrue(runCatching { resolveOfflineChatExportFile(temporary.root, file.toURI().toString(), "viewer") }.isFailure)
    }
}
