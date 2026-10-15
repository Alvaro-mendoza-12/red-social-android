package conectate.com.core.media

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.Timeout
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Actual callback reader and cancellation; fake calls perform zero network traffic. */
class StorageReadDownloadsTest {
    private val url = conectate.com.data.localfallback.UbuntuStorageUrl.baseUrl + "/storage/v1/object/public/chat-media/a/file.bin"
    private val a = StorageReadCredential(StorageReadIdentity("a", 1), "synthetic.jwt")
    @After fun reset() { StorageReadSession.install { null } }

    @Test fun successfulSmallReadClosesResponseAndBoundsAllocation() {
        StorageReadSession.install { a }
        val body = Body(byteArrayOf(1, 2, 3))
        val calls = Factory(body)
        val bytes = runBlocking { StorageReadDownloads.readBytes(url, 8, calls = calls) }
        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
        assertTrue(body.closed)
        assertEquals("no-store", calls.call.request().header("Cache-Control"))
        assertNull(calls.call.request().header("Authorization")) // only the real network interceptor adds Bearer
    }
    @Test fun declaredOversizeIsRejectedBeforeReadingAndClosesResponse() {
        val body = Body(byteArrayOf(1), declared = 20)
        assertThrows(IOException::class.java) { runBlocking { StorageReadDownloads.readBytes(url, 10, calls = Factory(body)) } }
        assertTrue(body.closed)
    }
    @Test fun unknownLengthCannotExceedTheActualReadLimit() {
        val body = Body(ByteArray(11), declared = -1)
        assertThrows(IOException::class.java) { runBlocking { StorageReadDownloads.readBytes(url, 10, calls = Factory(body)) } }
        assertTrue(body.closed)
    }
    @Test fun truncatedLengthAndEmptyBodyAreRejected() {
        for (body in listOf(Body(byteArrayOf(1), declared = 2), Body(byteArrayOf()))) {
            assertThrows(IOException::class.java) { runBlocking { StorageReadDownloads.readBytes(url, 8, calls = Factory(body)) } }
            assertTrue(body.closed)
        }
    }
    @Test fun failedHttpDoesNotReturnBodyBytes() {
        val body = Body(byteArrayOf(1, 2))
        assertThrows(IOException::class.java) { runBlocking { StorageReadDownloads.readBytes(url, 8, calls = Factory(body, code = 403)) } }
        assertTrue(body.closed)
    }
    @Test fun redirectIsRejectedAsANonSuccessfulResponse() {
        val body = Body(byteArrayOf(1, 2))
        assertThrows(IOException::class.java) { runBlocking { StorageReadDownloads.readBytes(url, 8, calls = Factory(body, code = 302)) } }
        assertTrue(body.closed)
    }
    @Test fun noCleartextReadIsSubmitted() {
        val calls = Factory(Body(byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { StorageReadDownloads.readBytes(url.replace("https", "http"), 8, calls = calls) } }
        assertFalse(calls.initialized)
    }
    @Test fun logoutDuringReadCannotPublishBytes() {
        var current: StorageReadCredential? = a
        StorageReadSession.install { current }
        val body = Body(byteArrayOf(1, 2), duringRead = { current = null })
        assertThrows(IOException::class.java) { runBlocking { StorageReadDownloads.readBytes(url, 8, calls = Factory(body)) } }
        assertTrue(body.closed)
    }
    @Test fun mimeMismatchDoesNotPublishAFile() {
        val output = File.createTempFile("storage-read-mime-", ".part")
        val body = Body(byteArrayOf(1, 2), mime = "application/pdf")
        assertThrows(IOException::class.java) {
            runBlocking { StorageReadDownloads.downloadToFile(url, output, 8, allowedMimeTypes = { it.startsWith("audio/") }, calls = Factory(body)) }
        }
        assertFalse(output.exists())
        assertTrue(body.closed)
    }
    @Test fun cancellationBeforeCallbackCancelsCallAndClosesLateResponse() = runBlocking {
        val body = Body(byteArrayOf(1, 2))
        val calls = Factory(body, immediate = false)
        val task = async { StorageReadDownloads.readBytes(url, 8, calls = calls) }
        yield()
        task.cancel()
        calls.respond()
        task.join()
        assertTrue(calls.call.isCanceled())
        assertTrue(body.closed)
    }
    @Test fun cancellationDuringFileReadDeletesTemporaryFileAfterClosingStream() = runBlocking {
        val output = File.createTempFile("storage-read-cancel-", ".part")
        lateinit var task: kotlinx.coroutines.Deferred<Long>
        val body = Body(byteArrayOf(1, 2), duringRead = { task.cancel() })
        val calls = Factory(body, immediate = false)
        task = async { StorageReadDownloads.downloadToFile(url, output, 8, calls = calls) }
        yield()
        calls.respond()
        task.join()
        assertTrue(calls.call.isCanceled())
        assertFalse(output.exists())
        assertTrue(body.closed)
    }

    private class Body(bytes: ByteArray, private val declared: Long = bytes.size.toLong(), private val mime: String = "application/octet-stream", duringRead: () -> Unit = {}) : ResponseBody() {
        var closed = false
        private val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { val result = super.read(buffer, offset, length); duringRead(); return result }
            override fun close() { closed = true; super.close() }
        }
        private val data = (input as InputStream).source().buffer()
        override fun contentType(): MediaType = mime.toMediaType()
        override fun contentLength(): Long = declared
        override fun source(): BufferedSource = data
    }
    private class Factory(private val body: Body, private val code: Int = 200, private val immediate: Boolean = true) : Call.Factory {
        lateinit var call: FakeCall
        var initialized = false
        private lateinit var callback: Callback
        override fun newCall(request: Request): Call {
            initialized = true
            call = FakeCall(request) { provided -> callback = provided; if (immediate) respond() }
            return call
        }
        fun respond() {
            callback.onResponse(call, Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("Fixture").body(body).build())
        }
    }
    private class FakeCall(private val request: Request, private val response: (Callback) -> Unit) : Call {
        private var executed = false
        private var cancelled = false
        private val timeout = Timeout()
        override fun request(): Request = request
        override fun execute(): Response = throw UnsupportedOperationException("No blocking or actual network")
        override fun enqueue(callback: Callback) { executed = true; response(callback) }
        override fun cancel() { cancelled = true }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = timeout
        override fun clone(): Call = FakeCall(request, response)
    }
}
