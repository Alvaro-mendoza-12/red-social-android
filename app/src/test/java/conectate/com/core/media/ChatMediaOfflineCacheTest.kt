package conectate.com.core.media

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaOfflineCacheTest {

    @Test
    fun `opened image is available through a durable account scoped file URI`() = runBlocking {
        withTemporaryDirectory { root ->
            var now = 1_000L
            val cache = cache(root, accountId = "Viewer-A", now = { now })
            val source = "https://cdn.example.test/chat-media/photo.jpg?temporary=1"

            val stored = cache.storeForTest(
                source = source,
                contentType = "image/jpeg",
                declaredBytes = 4L,
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            ).getOrThrow()

            assertTrue(stored.isFile)
            assertEquals(4L, stored.length())
            now += 10L
            val uri = cache.resolveCachedUri(source)
            assertNotNull(uri)
            assertTrue(uri!!.startsWith("file:"))
            assertEquals(now, stored.lastModified())
        }
    }

    @Test
    fun `offline media never crosses account directories`() = runBlocking {
        withTemporaryDirectory { root ->
            val source = "https://cdn.example.test/chat-media/voice.m4a"
            val firstAccount = cache(root, accountId = "first-account")
            firstAccount.storeForTest(
                source = source,
                contentType = "audio/mp4",
                declaredBytes = 3L,
                input = ByteArrayInputStream(byteArrayOf(4, 5, 6))
            ).getOrThrow()

            val secondAccount = cache(root, accountId = "second-account")
            assertNull(secondAccount.resolveCachedUri(source))
            assertNotNull(firstAccount.resolveCachedUri(source))
        }
    }

    @Test
    fun `least recently used entries are evicted before exceeding quota`() = runBlocking {
        withTemporaryDirectory { root ->
            var now = 1_000L
            val cache = cache(
                root = root,
                accountId = "viewer",
                now = { now },
                quotaBytes = 6L,
                entryBytes = 4L
            )
            val first = "https://cdn.example.test/chat-media/first.jpg"
            val second = "https://cdn.example.test/chat-media/second.jpg"

            cache.storeForTest(
                first,
                "image/jpeg",
                4L,
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            ).getOrThrow()
            now += 10L
            cache.storeForTest(
                second,
                "image/jpeg",
                4L,
                ByteArrayInputStream(byteArrayOf(5, 6, 7, 8))
            ).getOrThrow()

            assertNull(cache.resolveCachedUri(first))
            assertNotNull(cache.resolveCachedUri(second))
        }
    }

    @Test
    fun `unsupported or oversized data is rejected without a partial entry`() = runBlocking {
        withTemporaryDirectory { root ->
            val cache = cache(root, entryBytes = 3L)

            val unsupported = cache.storeForTest(
                source = "https://cdn.example.test/chat-media/document.pdf",
                contentType = "application/pdf",
                declaredBytes = 2L,
                input = ByteArrayInputStream(byteArrayOf(1, 2))
            )
            val oversized = cache.storeForTest(
                source = "https://cdn.example.test/chat-media/long.mp3",
                contentType = "audio/mpeg",
                declaredBytes = 4L,
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            )

            assertTrue(unsupported.isFailure)
            assertTrue(oversized.isFailure)
            assertNull(cache.resolveCachedUri("https://cdn.example.test/chat-media/document.pdf"))
            assertNull(cache.resolveCachedUri("https://cdn.example.test/chat-media/long.mp3"))
        }
    }

    @Test
    fun `clear only removes active account media`() = runBlocking {
        withTemporaryDirectory { root ->
            val source = "https://cdn.example.test/chat-media/image.webp"
            val first = cache(root, accountId = "first")
            val second = cache(root, accountId = "second")
            first.storeForTest(source, "image/webp", 2L, ByteArrayInputStream(byteArrayOf(1, 2))).getOrThrow()
            second.storeForTest(source, "image/webp", 2L, ByteArrayInputStream(byteArrayOf(3, 4))).getOrThrow()

            assertTrue(first.clearCurrentAccount())
            assertNull(first.resolveCachedUri(source))
            assertNotNull(second.resolveCachedUri(source))
        }
    }

    @Test
    fun `disabled automatic media never starts a remote cache request`() = runBlocking {
        withTemporaryDirectory { root ->
            val requests = AtomicInteger(0)
            val cache = ChatMediaOfflineCache(
                rootDirectory = root,
                accountIdProvider = { "viewer" },
                automaticMediaEnabled = { false },
                availableBytes = { Long.MAX_VALUE / 4L },
                minFreeSpaceBytes = 0L,
                connectionFactory = { url: URL ->
                    requests.incrementAndGet()
                    object : HttpURLConnection(url) {
                        override fun connect() = Unit
                        override fun disconnect() = Unit
                        override fun usingProxy() = false
                    }
                }
            )

            cache.cacheOpenedMedia(
                source = "https://cdn.example.test/chat-media/photo.jpg",
                resolvedUrl = "https://cdn.example.test/chat-media/photo.jpg"
            )

            assertEquals(0, requests.get())
            assertNull(cache.resolveCachedUri("https://cdn.example.test/chat-media/photo.jpg"))
        }
    }

    private fun cache(
        root: File,
        accountId: String = "viewer",
        now: () -> Long = { System.currentTimeMillis() },
        quotaBytes: Long = 1_024L,
        entryBytes: Long = 512L
    ): ChatMediaOfflineCache = ChatMediaOfflineCache(
        rootDirectory = root,
        accountIdProvider = { accountId },
        automaticMediaEnabled = { true },
        nowEpochMs = now,
        availableBytes = { Long.MAX_VALUE / 4L },
        maxAccountCacheBytes = quotaBytes,
        maxEntryBytes = entryBytes,
        minFreeSpaceBytes = 0L
    )

    private suspend fun withTemporaryDirectory(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("chat-media-offline-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
