package conectate.com.data.supabase

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StorageUploadCallTest {
    private val endpoint = "https://uploads.example.test/storage/v1/upload/resumable".toHttpUrl()

    @Test fun actualCreationEnablesResumingWithoutAnOptionsAdvertisement() = runTest {
        val call = ControlledCall()
        val session = async { call.createTusSessionOrNull(endpoint) }
        runCurrent()
        call.callback.onResponse(call, call.response("".toResponseBody(), 201, "/storage/v1/upload/resumable/owned"))
        assertEquals(endpoint.toString() + "/owned", session.await().toString())
        assertEquals(1, call.attempts)
    }

    @Test fun onlyAnExplicitlyUnsupportedCreationRouteAllowsLegacyUpload() = runTest {
        for (status in listOf(404, 405, 501)) {
            val call = ControlledCall()
            val session = async { call.createTusSessionOrNull(endpoint) }
            runCurrent()
            call.callback.onResponse(call, call.response("".toResponseBody(), status))
            assertNull(session.await())
        }
    }

    @Test fun authenticationThrottlingAndServerFailuresNeverAllowASecondStreamUpload() = runTest {
        for (status in listOf(400, 401, 403, 409, 413, 429, 500, 502, 503)) {
            val call = ControlledCall()
            val session = async { runCatching { call.createTusSessionOrNull(endpoint) } }
            runCurrent()
            call.callback.onResponse(call, call.response("".toResponseBody(), status))
            assertTrue("Unexpected legacy fallback for HTTP $status", session.await().exceptionOrNull() is IOException)
            assertEquals(1, call.attempts)
        }
    }

    @Test fun temporaryOutageSurfacesSafeFeedbackWithoutAutomaticRecovery() = runTest {
        val call = ControlledCall()
        val session = async { runCatching { call.createTusSessionOrNull(endpoint) } }
        runCurrent()
        val reference = "6dcf4279-a3f7-4530-816e-01cb1a72d5d7"
        val response = call.response("internal failure details".toResponseBody(), 503)
            .newBuilder().header("X-Request-ID", reference).build()
        call.callback.onResponse(call, response)
        val failure = session.await().exceptionOrNull() as StorageUploadHttpException
        assertEquals(503, failure.statusCode)
        assertEquals("Las subidas están temporalmente interrumpidas. Vuelve a intentarlo más tarde. Referencia $reference.", failure.message)
        assertFalse(failure.canRecoverOffset)
        assertEquals(1, call.attempts)
    }

    @Test fun offsetRecoveryStillUsesConflictsAndAmbiguousTransferFailures() {
        for (status in listOf(409, 500, 502, 504)) {
            val response = ControlledCall().response("".toResponseBody(), status)
            response.use { assertTrue(it.storageUploadFailure("fallo").canRecoverOffset) }
        }
        for (status in listOf(401, 403, 413, 415, 422, 429, 503)) {
            val response = ControlledCall().response("".toResponseBody(), status)
            response.use { assertFalse(it.storageUploadFailure("fallo").canRecoverOffset) }
        }
    }

    @Test fun uploadFeedbackDoesNotEchoAnUntrustedReference() {
        val response = ControlledCall().response("private details".toResponseBody(), 413)
            .newBuilder().header("X-Request-ID", "untrusted value").build()
        response.use {
            assertEquals("El archivo supera el tamaño permitido. Selecciona uno más pequeño.", it.storageUploadFailure("fallo").message)
        }
    }

    @Test fun connectionLossDuringCreationRetainsFailureInsteadOfDowngradingUpload() = runTest {
        val call = ControlledCall()
        val session = async { runCatching { call.createTusSessionOrNull(endpoint) } }
        runCurrent()
        call.callback.onFailure(call, IOException("connection lost"))
        assertTrue(session.await().exceptionOrNull() is IOException)
        assertEquals(1, call.attempts)
    }

    @Test fun createdSessionCannotSendCredentialsToAnotherOrigin() = runTest {
        val call = ControlledCall()
        val session = async { runCatching { call.createTusSessionOrNull(endpoint) } }
        runCurrent()
        call.callback.onResponse(call, call.response("".toResponseBody(), 201, "https://other.example.test/storage/v1/upload/resumable/owned"))
        assertTrue(session.await().exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun cancellingTheCoroutineCancelsTheUnderlyingTransfer() = runTest {
        val call = ControlledCall()
        val upload = async { call.awaitStorageResponse() }
        runCurrent()
        upload.cancelAndJoin()
        assertTrue(call.cancelled)
    }

    @Test fun lateResponseAfterCancellationIsClosedInsteadOfLeaked() = runTest {
        val call = ControlledCall()
        var closed = false
        val body = object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = 0L
            override fun source(): BufferedSource = Buffer()
            override fun close() { closed = true }
        }
        val upload = async { call.awaitStorageResponse() }
        runCurrent()
        upload.cancelAndJoin()
        call.callback.onResponse(call, call.response(body))
        assertTrue(closed)
    }

    @Test fun interruptedTransportPropagatesFailureWithoutAnImplicitRetry() = runTest {
        val call = ControlledCall()
        val upload = async { runCatching { call.awaitStorageResponse() } }
        runCurrent()
        call.callback.onFailure(call, IOException("connection lost"))
        assertTrue(upload.await().exceptionOrNull() is IOException)
        assertEquals(1, call.attempts)
    }

    @Test fun serverResponseRemainsOwnedByTheCaller() = runTest {
        val call = ControlledCall()
        val upload = async { call.awaitStorageResponse() }
        runCurrent()
        call.callback.onResponse(call, call.response("ok".toResponseBody()))
        upload.await().use { assertEquals("ok", it.body!!.string()) }
    }

    private class ControlledCall : Call {
        lateinit var callback: Callback
        var cancelled = false
        var attempts = 0
        override fun request() = Request.Builder().url("https://uploads.example.test/file").build()
        override fun execute(): Response = error("Blocking transport must not be used")
        override fun enqueue(responseCallback: Callback) { attempts++; callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = attempts > 0
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = ControlledCall()
        fun response(body: ResponseBody, code: Int = 204, location: String? = null) = Response.Builder()
            .request(request()).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic").body(body)
            .apply { if (location != null) header("Location", location) }.build()
    }
}
