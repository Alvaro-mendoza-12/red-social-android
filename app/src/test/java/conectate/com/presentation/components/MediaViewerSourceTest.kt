package conectate.com.presentation.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MediaViewerSourceTest {
    @Test fun `public and signed HTTP and bucket paths all consult the offline resolver`() {
        listOf(
            "https://cdn.test/storage/v1/object/public/chat-media/photo.jpg",
            "https://cdn.test/storage/v1/object/sign/chat-media/video.mp4?token=temporary",
            "chat-media/photo.jpg"
        ).forEach { assertTrue(shouldResolveMediaViewerSource(it, true)) }
    }

    @Test fun `already local and generic non-chat viewers do not resolve again`() {
        listOf("file:/private/photo.media", "content://media/photo", "android.resource://app/1")
            .forEach { assertFalse(shouldResolveMediaViewerSource(it, true)) }
        assertFalse(shouldResolveMediaViewerSource("https://cdn.test/photo.jpg", false))
    }

    @Test fun `public remote URL opens the returned offline copy`() = runBlocking {
        var calls = 0
        val remote = "https://cdn.test/storage/v1/object/public/chat-media/video.mp4"
        val result = resolveMediaViewerSource(remote, {
            assertEquals(remote, it)
            calls++
            "file:/private/downloaded.media"
        })
        assertEquals("file:/private/downloaded.media", result)
        assertEquals(1, calls)
    }

    @Test fun `failed access does not fall back to the original private URL`() = runBlocking {
        assertNull(resolveMediaViewerSource("https://cdn.test/private.jpg", { null }))
        assertNull(resolveMediaViewerSource("chat-media/private.jpg", { error("Account changed") }))
    }

    @Test fun `slow resolution is bounded instead of showing an infinite spinner`() = runBlocking {
        assertNull(resolveMediaViewerSource("chat-media/slow.mp4", { delay(100); "remote" }, timeoutMs = 1))
    }

    @Test fun `leaving viewer cancels resolution without an error fallback`() = runBlocking {
        var cancelled = false
        try {
            resolveMediaViewerSource("chat-media/photo.jpg", { throw CancellationException("Left screen") })
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
