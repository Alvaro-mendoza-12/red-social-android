package conectate.com.core.media

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaOfflineCacheTest {

    @Test fun `loaded previews persist without a network request and reject another account`() = runBlocking {
        withTemporaryDirectory { root ->
            val source = "https://cdn.example.test/chat-media/photo.jpg?width=1080"
            val first = cache(root, accountId = "first")
            val bytes = byteArrayOf(1, 2, 3, 4)
            assertTrue(first.storeLoadedPreview(source, bytes, "first"))
            val file = File(java.net.URI(requireNotNull(first.resolveCachedUri(source))))
            assertTrue(bytes.contentEquals(file.readBytes()))
            val second = cache(root, accountId = "second")
            assertTrue(!second.storeLoadedPreview(source, bytes, "first"))
            assertNull(second.resolveCachedUri(source))
        }
    }

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

    @Test
    fun `explicitly opened media is cached even when automatic downloads are disabled`() = runBlocking {
        withTemporaryDirectory { root ->
            val source = "https://cdn.example.test/chat-media/photo.jpg"
            val cache = ChatMediaOfflineCache(
                rootDirectory = root,
                accountIdProvider = { "viewer" },
                automaticMediaEnabled = { false },
                availableBytes = { Long.MAX_VALUE / 4L },
                minFreeSpaceBytes = 0L,
                connectionFactory = { url: URL ->
                    object : HttpURLConnection(url) {
                        override fun connect() = Unit
                        override fun disconnect() = Unit
                        override fun usingProxy() = false
                        override fun getResponseCode() = HTTP_OK
                        override fun getContentType() = "image/jpeg"
                        override fun getContentLengthLong() = 4L
                        override fun getInputStream() = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
                    }
                }
            )

            cache.cacheExplicitlyOpenedMedia(source, source)

            repeat(100) {
                if (cache.resolveCachedUri(source) != null) return@repeat
                delay(10L)
            }
            assertNotNull(cache.resolveCachedUri(source))
        }
    }

    @Test
    fun `opened prefetched media survives eviction and a new cache instance`() = runBlocking {
        withTemporaryDirectory { root ->
            val cache = cache(root, quotaBytes = 4L, entryBytes = 4L)
            val source = "https://cdn.example.test/chat-media/kept.jpg"
            cache.storeForTest(source, "image/jpeg", 4L, ByteArrayInputStream(ByteArray(4) { 1 })).getOrThrow()
            assertNotNull(cache.resolveCachedUri(source, retain = true))
            cache.storeForTest("chat-media/new.jpg", "image/jpeg", 4L, ByteArrayInputStream(ByteArray(4) { 2 })).getOrThrow()
            cache.storeForTest("chat-media/newer.jpg", "image/jpeg", 4L, ByteArrayInputStream(ByteArray(4) { 3 })).getOrThrow()

            val restarted = cache(root, quotaBytes = 4L, entryBytes = 4L)
            assertNotNull(restarted.resolveCachedUri(source))
            assertNull(restarted.resolveCachedUri("chat-media/new.jpg"))
            assertNotNull(restarted.resolveCachedUri("chat-media/newer.jpg"))
            val usage = restarted.currentAccountUsage()!!
            assertEquals(4L, usage.retainedBytes)
            assertEquals(4L, usage.prefetchedBytes)
            assertEquals(2, usage.fileCount)
        }
    }

    @Test
    fun `retained file may exceed prefetch quota without deleting old copies`() = runBlocking {
        withTemporaryDirectory { root ->
            val cache = cache(root, quotaBytes = 3L, entryBytes = 2L)
            cache.storeForTest("chat-media/old.jpg", "image/jpeg", 2L, ByteArrayInputStream(ByteArray(2) { 1 })).getOrThrow()
            cache.storeForTest("chat-media/opened.mp4", "video/mp4", 5L, ByteArrayInputStream(ByteArray(5) { 2 }), retain = true).getOrThrow()
            assertNotNull(cache.resolveCachedUri("chat-media/old.jpg"))
            assertEquals(5L, cache.currentAccountUsage()!!.retainedBytes)
        }
    }

    @Test
    fun `truncated response cannot replace healthy entries or appear offline`() = runBlocking {
        withTemporaryDirectory { root ->
            val cache = cache(root, quotaBytes = 4L, entryBytes = 4L)
            cache.storeForTest("chat-media/old.jpg", "image/jpeg", 4L, ByteArrayInputStream(ByteArray(4) { 1 })).getOrThrow()
            val result = cache.storeForTest("chat-media/truncated.jpg", "image/jpeg", 4L, ByteArrayInputStream(ByteArray(2) { 2 }))
            assertTrue(result.isFailure)
            assertNull(cache.resolveCachedUri("chat-media/truncated.jpg"))
            assertNotNull(cache.resolveCachedUri("chat-media/old.jpg"))
            assertEquals(0, root.walkTopDown().count { it.name.endsWith(".part") })
        }
    }

    @Test
    fun `low space never discards retained media to make room`() = runBlocking {
        withTemporaryDirectory { root ->
            var freeBytes = 100L
            val cache = ChatMediaOfflineCache(
                rootDirectory = root,
                accountIdProvider = { "viewer" },
                automaticMediaEnabled = { true },
                availableBytes = { freeBytes },
                minFreeSpaceBytes = 10L
            )
            cache.storeForTest("chat-media/kept.jpg", "image/jpeg", 2L, ByteArrayInputStream(byteArrayOf(1, 2)), retain = true).getOrThrow()
            freeBytes = 11L
            assertTrue(cache.storeForTest("chat-media/new.jpg", "image/jpeg", 2L, ByteArrayInputStream(byteArrayOf(3, 4)), retain = true).isFailure)
            assertNotNull(cache.resolveCachedUri("chat-media/kept.jpg"))
        }
    }

    @Test
    fun `storage confirmation cannot clear a different signed in account`() = runBlocking {
        withTemporaryDirectory { root ->
            val first = cache(root, accountId = "first")
            val second = cache(root, accountId = "second")
            second.storeForTest("chat-media/kept.jpg", "image/jpeg", 2L, ByteArrayInputStream(byteArrayOf(1, 2)), retain = true).getOrThrow()
            val firstKey = first.currentAccountUsage()!!.accountKey
            assertTrue(!second.clearCurrentAccount(firstKey))
            assertNotNull(second.resolveCachedUri("chat-media/kept.jpg"))
            assertTrue(second.clearCurrentAccount(second.currentAccountUsage()!!.accountKey))
            assertNull(second.resolveCachedUri("chat-media/kept.jpg"))
        }
    }

    @Test
    fun `clear rejects an outstanding download instead of restoring deleted copies`() = runBlocking {
        withTemporaryDirectory { root ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val source = "https://cdn.example.test/chat-media/slow.jpg"
            val cache = ChatMediaOfflineCache(
                rootDirectory = root,
                accountIdProvider = { "viewer" },
                automaticMediaEnabled = { true },
                availableBytes = { Long.MAX_VALUE / 4L },
                minFreeSpaceBytes = 0L,
                connectionFactory = { url ->
                    object : HttpURLConnection(url) {
                        override fun connect() = Unit
                        override fun disconnect() { finished.countDown() }
                        override fun usingProxy() = false
                        override fun getResponseCode(): Int {
                            started.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            return HTTP_OK
                        }
                        override fun getContentType() = "image/jpeg"
                        override fun getContentLengthLong() = 2L
                        override fun getInputStream() = ByteArrayInputStream(byteArrayOf(1, 2))
                    }
                }
            )
            try {
                cache.cacheExplicitlyOpenedMedia(source, source)
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertTrue(cache.clearCurrentAccount())
            } finally {
                release.countDown()
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertNull(cache.resolveCachedUri(source))
        }
    }

    @Test
    fun `resolution from an old account cannot enqueue media under the new account`() = runBlocking {
        withTemporaryDirectory { root ->
            val requests = AtomicInteger()
            val cache = ChatMediaOfflineCache(
                rootDirectory = root,
                accountIdProvider = { "new-account" },
                automaticMediaEnabled = { true },
                connectionFactory = { _: URL ->
                    requests.incrementAndGet()
                    error("Must not download for another account")
                }
            )
            cache.cacheExplicitlyOpenedMedia("chat-media/private.jpg", "https://cdn.test/private.jpg", expectedAccountId = "old-account")
            cache.cacheOpenedMedia("chat-media/private.jpg", "https://cdn.test/private.jpg", expectedAccountId = "old-account")
            assertEquals(0, requests.get())
            assertEquals(0, cache.currentAccountUsage()!!.fileCount)
        }
    }

    @Test
    fun `opening a download already in flight promotes the completed copy`() = runBlocking {
        withTemporaryDirectory { root ->
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val requests = AtomicInteger()
            val source = "https://cdn.example.test/chat-media/slow.jpg"
            val cache = ChatMediaOfflineCache(
                rootDirectory = root,
                accountIdProvider = { "viewer" },
                automaticMediaEnabled = { true },
                availableBytes = { Long.MAX_VALUE / 4L },
                minFreeSpaceBytes = 0L,
                connectionFactory = { url ->
                    requests.incrementAndGet()
                    object : HttpURLConnection(url) {
                        override fun connect() = Unit
                        override fun disconnect() { finished.countDown() }
                        override fun usingProxy() = false
                        override fun getResponseCode(): Int {
                            started.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            return HTTP_OK
                        }
                        override fun getContentType() = "image/jpeg"
                        override fun getContentLengthLong() = 2L
                        override fun getInputStream() = ByteArrayInputStream(byteArrayOf(1, 2))
                    }
                }
            )
            try {
                cache.cacheOpenedMedia(source, source)
                assertTrue(started.await(5, TimeUnit.SECONDS))
                cache.cacheExplicitlyOpenedMedia(source, source)
            } finally {
                release.countDown()
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, requests.get())
            assertEquals(2L, cache.currentAccountUsage()!!.retainedBytes)
        }
    }

    @Test
    fun `a slow video cannot block a received sticker from becoming available offline`() = runBlocking {
        withTemporaryDirectory { root ->
            val videoStarted = CountDownLatch(1)
            val releaseVideo = CountDownLatch(1)
            val videoFinished = CountDownLatch(1)
            val stickerStored = CountDownLatch(1)
            val sticker = "https://cdn.example.test/chat-media/sticker.webp"
            val cache = ChatMediaOfflineCache(
                rootDirectory = root,
                accountIdProvider = { "viewer" },
                automaticMediaEnabled = { true },
                availableBytes = { Long.MAX_VALUE / 4L },
                minFreeSpaceBytes = 0L,
                onCachedMedia = { _, key, _ -> if (key.contains("sticker.webp")) stickerStored.countDown() },
                connectionFactory = { url ->
                    object : HttpURLConnection(url) {
                        private val video = url.path.endsWith(".mp4")
                        override fun connect() = Unit
                        override fun usingProxy() = false
                        override fun disconnect() { if (video) videoFinished.countDown() }
                        override fun getResponseCode(): Int {
                            if (video) { videoStarted.countDown(); check(releaseVideo.await(15, TimeUnit.SECONDS)) }
                            return HTTP_OK
                        }
                        override fun getContentType() = if (video) "video/mp4" else "image/webp"
                        override fun getContentLengthLong() = 2L
                        override fun getInputStream() = ByteArrayInputStream(byteArrayOf(1, 2))
                    }
                },
            )
            try {
                val video = "https://cdn.example.test/chat-media/slow.mp4"
                cache.cacheOpenedMedia(video, video)
                assertTrue(videoStarted.await(5, TimeUnit.SECONDS))
                cache.cacheOpenedMedia(sticker, sticker)
                assertTrue(stickerStored.await(5, TimeUnit.SECONDS))
                assertNotNull(cache.resolveCachedUri(sticker))
            } finally { releaseVideo.countDown(); assertTrue(videoFinished.await(5, TimeUnit.SECONDS)) }
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
