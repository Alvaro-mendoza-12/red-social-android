package conectate.com.core.media

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/** No networking: invoke the real per-attempt interceptor with synthetic responses. */
class StorageReadInterceptorsTest {
    private val backend = "https://api.example.test"
    private val edge = "https://media.example.test"
    private val storage = "$backend/storage/v1/object/public/chat-media/u/a.jpg"
    private val a = StorageReadCredential(StorageReadIdentity("a", 1), "first.jwt")

    @Test fun applicationCaptureRemovesCallerBearerAndKeepsIdentity() {
        val chain = Chain(Request.Builder().url(storage).header("Authorization", "old.jwt").build())
        StorageReadRequestInterceptor({ backend }, { a }, { a }).intercept(chain).close()
        assertNull(chain.sent!!.header("Authorization"))
        assertEquals(a.identity, chain.sent!!.tag(StorageReadRequestContext::class.java)!!.identity)
    }
    @Test fun edgeFailureRetriesCanonicalReadWithTheSameRange() {
        val original = Request.Builder().url(storage).header("Range", "bytes=0-9").build()
        val chain = Chain(original, status = { if (it.url.host == "media.example.test") 503 else 206 })
        StorageReadRequestInterceptor({ backend }, { a }, { a }, edge, backend).intercept(chain).use {
            assertEquals(206, it.code)
        }
        assertEquals(listOf(storage.replace(backend, edge), storage), chain.attempts.map { it.url.toString() })
        assertTrue(chain.attempts.all { it.header("Range") == "bytes=0-9" })
    }

    @Test fun realReadInterceptorsPreserveRangeAndRefreshAuthorizationWhenEdgeFallsBack() {
        var current = a
        val attempts = mutableListOf<Request>()
        val network = StorageReadNetworkInterceptor({ backend }, { current })
        val original = Request.Builder().url(storage).header("Range", "bytes=0-2")
            .header("Authorization", "caller-must-not-win").header("Cookie", "origin-only").build()
        val chain = Chain(original, next = { request ->
            network.intercept(Chain(request, status = { authorized ->
                attempts += authorized
                if (authorized.url.host == "media.example.test") {
                    current = StorageReadCredential(a.identity, "renewed.jwt")
                    503
                } else 206
            }))
        })
        StorageReadRequestInterceptor({ backend }, { current }, { current }, edge, backend).intercept(chain).use {
            assertEquals(206, it.code)
            assertArrayEquals(byteArrayOf(1, 2, 3), it.body!!.bytes())
        }
        assertEquals(listOf("media.example.test", "api.example.test"), attempts.map { it.url.host })
        assertEquals(listOf("Bearer first.jwt", "Bearer renewed.jwt"), attempts.map { it.header("Authorization") })
        assertTrue(attempts.all { it.header("Range") == "bytes=0-2" })
        assertNull(attempts.first().header("Cookie"))
    }

    @Test fun accountChangeDuringEdgeFailureCannotSendTheNewAccountsTokenToTheFallback() {
        var current = a
        val attempts = mutableListOf<Request>()
        val network = StorageReadNetworkInterceptor({ backend }, { current })
        val chain = Chain(Request.Builder().url(storage).build(), next = { request ->
            network.intercept(Chain(request, status = { authorized ->
                attempts += authorized
                current = StorageReadCredential(StorageReadIdentity("b", 2), "other.jwt")
                503
            }))
        })
        assertThrows(IOException::class.java) {
            StorageReadRequestInterceptor({ backend }, { current }, { current }, edge, backend).intercept(chain)
        }
        assertEquals(1, attempts.size)
        assertEquals("media.example.test", attempts.single().url.host)
        assertEquals("Bearer first.jwt", attempts.single().header("Authorization"))
    }

    @Test fun openingEdgeConnectionFailureRetriesTheCanonicalOriginWithTheRealNetworkGate() {
        val attempts = mutableListOf<Request>()
        val network = StorageReadNetworkInterceptor({ backend }, { a })
        val chain = Chain(Request.Builder().url(storage).build(), next = { request ->
            network.intercept(Chain(request, status = { authorized ->
                attempts += authorized
                if (authorized.url.host == "media.example.test") throw IOException("Synthetic edge interruption")
                200
            }))
        })
        StorageReadRequestInterceptor({ backend }, { a }, { a }, edge, backend).intercept(chain).use {
            assertArrayEquals(byteArrayOf(1, 2, 3), it.body!!.bytes())
        }
        assertEquals(listOf("media.example.test", "api.example.test"), attempts.map { it.url.host })
        assertTrue(attempts.all { it.header("Authorization") == "Bearer first.jwt" })
    }
    @Test fun edgeDenialDoesNotRetryAndOnlyExactEdgeUrlGetsBearer() {
        val original = Request.Builder().url(storage).build()
        val chain = Chain(original, status = { 403 })
        StorageReadRequestInterceptor({ backend }, { a }, { a }, edge, backend).intercept(chain).use {
            assertEquals(403, it.code)
        }
        val accelerated = chain.attempts.single()
        val network = StorageReadNetworkInterceptor({ backend }, { a })
        val authorized = Chain(accelerated)
        network.intercept(authorized).close()
        assertEquals("Bearer first.jwt", authorized.sent!!.header("Authorization"))
        val redirected = Chain(accelerated.newBuilder().url("$edge/other").build())
        network.intercept(redirected).close()
        assertNull(redirected.sent!!.header("Authorization"))
    }
    @Test fun ownGetAddsCurrentBearerAndNoStore() {
        val chain = Chain(request(storage))
        StorageReadNetworkInterceptor({ backend }, { a }).intercept(chain).close()
        assertEquals("Bearer first.jwt", chain.sent!!.header("Authorization"))
        assertEquals("no-store", chain.sent!!.header("Cache-Control"))
    }
    @Test fun ownHeadUsesTheSameAuthorizationGate() {
        val chain = Chain(request(storage).newBuilder().head().build())
        StorageReadNetworkInterceptor({ backend }, { a }).intercept(chain).close()
        assertEquals("Bearer first.jwt", chain.sent!!.header("Authorization"))
    }
    @Test fun refreshUsesNewTokenWithoutChangingAccount() {
        val refreshed = StorageReadCredential(a.identity, "second.jwt")
        val chain = Chain(request(storage))
        StorageReadNetworkInterceptor({ backend }, { refreshed }).intercept(chain).close()
        assertEquals("Bearer second.jwt", chain.sent!!.header("Authorization"))
    }
    @Test fun externalRedirectRemovesInheritedAuthorizationOnTheNextAttempt() {
        val redirected = request("https://external.test/file.jpg").newBuilder().header("Authorization", "Bearer first.jwt").build()
        val chain = Chain(redirected)
        StorageReadNetworkInterceptor({ backend }, { a }).intercept(chain).close()
        assertNull(chain.sent!!.header("Authorization"))
    }
    @Test fun sameOriginRedirectToNonStorageRouteAlsoRemovesBearer() {
        val chain = Chain(request("$backend/v1/auth/me").newBuilder().header("Authorization", "Bearer first.jwt").build())
        StorageReadNetworkInterceptor({ backend }, { a }).intercept(chain).close()
        assertNull(chain.sent!!.header("Authorization"))
    }
    @Test fun externalRequestRedirectingIntoStorageCannotAcquireAccountToken() {
        val chain = Chain(request(storage, startedAtStorageOrigin = false))
        StorageReadNetworkInterceptor({ backend }, { a }).intercept(chain).close()
        assertNull(chain.sent!!.header("Authorization"))
    }
    @Test fun portAndUserInfoDoNotBorrowAuthentication() {
        for (url in listOf("https://api.example.test:444/storage/v1/object/public/b/u/a", "https://name:pass@api.example.test/storage/v1/object/public/b/u/a")) {
            val chain = Chain(request(url).newBuilder().header("Authorization", "old.jwt").build())
            StorageReadNetworkInterceptor({ backend }, { a }).intercept(chain).close()
            assertNull(chain.sent!!.header("Authorization"))
        }
    }
    @Test fun accountSwitchCancelsBeforeSendingAnyRequest() {
        val chain = Chain(request(storage))
        val b = StorageReadCredential(StorageReadIdentity("b", 1), "second.jwt")
        assertThrows(IOException::class.java) { StorageReadNetworkInterceptor({ backend }, { b }).intercept(chain) }
        assertNull(chain.sent)
    }
    @Test fun logoutAndReloginSameAccountGenerationCancelOldRequest() {
        for (current in listOf(null, StorageReadCredential(StorageReadIdentity("a", 2), "second.jwt"))) {
            val chain = Chain(request(storage))
            assertThrows(IOException::class.java) { StorageReadNetworkInterceptor({ backend }, { current }).intercept(chain) }
            assertNull(chain.sent)
        }
    }
    @Test fun accountSwitchAfterResponseClosesBodyBeforeDelivery() {
        var current: StorageReadCredential? = a
        val chain = Chain(request(storage), beforeResponse = { current = null })
        assertThrows(IOException::class.java) { StorageReadNetworkInterceptor({ backend }, { current }).intercept(chain) }
    }
    @Test fun nextNetworkSourceReadCannotContinueAfterLogout() {
        var current: StorageReadCredential? = a
        val response = StorageReadNetworkInterceptor({ backend }, { current }).intercept(Chain(request(storage)))
        response.use {
            val stream = response.body!!.byteStream()
            assertEquals(3, stream.read(ByteArray(3)))
            current = null
            assertThrows(IOException::class.java) { stream.read() }
        }
    }
    @Test fun anonymousPublicStorageRequestHasNoJwt() {
        val chain = Chain(request(storage, identity = null))
        StorageReadNetworkInterceptor({ backend }, { null }).intercept(chain).close()
        assertNull(chain.sent!!.header("Authorization"))
    }
    @Test fun externalResponseRemainsReadableAcrossAuthenticationChanges() {
        var current: StorageReadCredential? = a
        val request = request("https://external.test/file.jpg", startedAtStorageOrigin = false)
        StorageReadNetworkInterceptor({ backend }, { current }).intercept(Chain(request)).use { response ->
            current = null
            assertArrayEquals(byteArrayOf(1, 2, 3), response.body!!.bytes())
        }
    }
    @Test fun accountChangeInsideNetworkReadDoesNotFillTheDownstreamBuffer() {
        var current: StorageReadCredential? = a
        val body = object : okhttp3.ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength(): Long = 1
            private val data = object : okio.Source {
                override fun read(sink: okio.Buffer, byteCount: Long): Long { sink.writeByte(42); current = null; return 1 }
                override fun timeout(): okio.Timeout = okio.Timeout.NONE
                override fun close() {}
            }.buffer()
            override fun source(): okio.BufferedSource = data
        }
        val chain = Chain(request(storage), body = body)
        StorageReadNetworkInterceptor({ backend }, { current }).intercept(chain).use { response ->
            val caller = byteArrayOf(7, 8, 9)
            assertThrows(IOException::class.java) { response.body!!.byteStream().read(caller) }
            assertArrayEquals(byteArrayOf(7, 8, 9), caller)
        }
    }
    private fun request(url: String, identity: StorageReadIdentity? = a.identity, startedAtStorageOrigin: Boolean = true): Request =
        Request.Builder().url(url).tag(StorageReadRequestContext::class.java, StorageReadRequestContext(identity, startedAtStorageOrigin)).build()

    private class Chain(private val original: Request, private val beforeResponse: () -> Unit = {}, private val body: okhttp3.ResponseBody? = null, private val status: (Request) -> Int = { 200 }, private val next: ((Request) -> Response)? = null) : Interceptor.Chain {
        val attempts = mutableListOf<Request>()
        val sent: Request? get() = attempts.lastOrNull()
        override fun request(): Request = original
        override fun proceed(request: Request): Response {
            attempts += request
            beforeResponse()
            next?.let { return it(request) }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status(request)).message("Synthetic")
                .body(body ?: byteArrayOf(1, 2, 3).toResponseBody()).build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = OkHttpClient().newCall(original)
        override fun connectTimeoutMillis(): Int = 1000
        override fun readTimeoutMillis(): Int = 1000
        override fun writeTimeoutMillis(): Int = 1000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
