package conectate.com.util

import conectate.com.core.network.PublicBackendDns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ChatAudioPlaybackCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val url = "https://media.example.test/storage/v1/object/public/chat-media/owner/voice.m4a"

    @Test fun productionUsesTheSharedPublicDnsClientWithoutCleartextRedirects() {
        val client = MediaPlaybackDataSources.networkClient()
        assertSame(PublicBackendDns, client.dns)
        assertFalse(client.followSslRedirects)
        assertSame(client, MediaPlaybackDataSources.networkClient())
    }

    @Test fun completeAudioIsCachedAndASecondOpeningNeedsNoNetwork() {
        val calls = AtomicInteger()
        val cache = ChatAudioPlaybackCache(folder.newFolder(), client(calls))
        val first = cache.download(url)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.readBytes())
        assertEquals(first, cache.download(url))
        assertEquals(1, calls.get())
    }

    @Test fun aFailedResponseDoesNotCreateAPlayableCacheFile() {
        val directory = folder.newFolder()
        val cache = ChatAudioPlaybackCache(directory, client(AtomicInteger(), status = 503))
        expectFailure { cache.download(url) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun incompleteAudioIsDeletedAndTheNextOpeningDownloadsAgain() {
        val directory = folder.newFolder()
        val calls = AtomicInteger()
        val cache = ChatAudioPlaybackCache(directory, client(calls))
        expectFailure { cache.store(url, byteArrayOf(1, 2).inputStream(), expectedBytes = 3) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertEquals(3L, cache.download(url).length())
        assertEquals(1, calls.get())
    }

    @Test fun oversizeAudioIsRejectedAndOldCompleteFilesAreEvicted() {
        val directory = folder.newFolder()
        val cache = ChatAudioPlaybackCache(directory, client(AtomicInteger()), maxFileBytes = 4, maxCacheBytes = 4)
        expectFailure { cache.store(url, ByteArray(5).inputStream()) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        val first = cache.store(url, ByteArray(3).inputStream())
        cache.store("$url?width=10", ByteArray(3).inputStream())
        assertFalse(first.exists())
        assertEquals(3L, directory.listFiles().orEmpty().sumOf { it.length() })
    }

    @Test fun cleartextAudioIsRejectedBeforeSendingARequest() {
        val calls = AtomicInteger()
        val cache = ChatAudioPlaybackCache(folder.newFolder(), client(calls))
        assertThrows(IllegalArgumentException::class.java) { cache.download(url.replace("https:", "http:")) }
        assertEquals(0, calls.get())
    }

    private fun client(calls: AtomicInteger, status: Int = 200) = OkHttpClient.Builder().addInterceptor { chain ->
        calls.incrementAndGet()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("QA")
            .body(byteArrayOf(1, 2, 3).toResponseBody()).build()
    }.build()

    private fun expectFailure(action: () -> Any) {
        assertThrows(IOException::class.java) { action() }
    }
}
