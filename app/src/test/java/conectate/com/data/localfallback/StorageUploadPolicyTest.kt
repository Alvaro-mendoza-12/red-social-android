package conectate.com.data.localfallback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StorageUploadPolicyTest {
    @Test fun successfulRawUploadNeverCallsMultipart() = runTest {
        var fallback = 0
        assertEquals("object", StorageUploadPolicy.upload({ Result.success("object") }, { fallback++; Result.success("other") }).getOrThrow())
        assertEquals(0, fallback)
    }
    @Test fun networkFailureIsPreservedWithoutDuplicateUpload() = runTest {
        var fallback = 0; val failure = IOException("response lost")
        val result = StorageUploadPolicy.upload<String>({ Result.failure(failure) }, { fallback++; Result.success("other") })
        assertSame(failure, result.exceptionOrNull()); assertEquals(0, fallback)
    }
    @Test fun scanAndAuthorizationFailuresNeverTryAnotherEndpoint() = runTest {
        for (reason in listOf("MALWARE_DETECTED", "SCAN_UNAVAILABLE", "SCAN_TIMEOUT", "FORBIDDEN", "CONFLICT", "PAYLOAD_TOO_LARGE")) {
            var fallback = 0; val failure = Exception(reason)
            assertSame(failure, StorageUploadPolicy.upload<String>({ Result.failure(failure) }, { fallback++; Result.success("other") }).exceptionOrNull())
            assertEquals(0, fallback)
        }
    }
    @Test fun explicitUnsupportedRouteCallsFallbackExactlyOnce() = runTest {
        var raw = 0; var fallback = 0
        val result = StorageUploadPolicy.upload<String>({ raw++; Result.failure(StorageRawRouteUnavailableException()) }, { fallback++; Result.success("multipart") })
        assertEquals("multipart", result.getOrThrow()); assertEquals(1, raw); assertEquals(1, fallback)
    }
    @Test fun cancellationResultIsRethrownBeforeFallback() = runTest {
        var fallback = 0; val cancelled = CancellationException("cancelled")
        try {
            StorageUploadPolicy.upload<String>({ Result.failure(cancelled) }, { fallback++; Result.success("other") }); fail("Cancellation swallowed")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        assertEquals(0, fallback)
    }
    @Test fun multipartCancellationAlsoPropagates() = runTest {
        val cancelled = CancellationException("cancelled")
        try {
            StorageUploadPolicy.upload<String>({ Result.failure(StorageRawRouteUnavailableException()) }, { Result.failure(cancelled) }); fail("Cancellation swallowed")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
    }
    @Test fun onlyExplicitUnimplementedRoutesPermitFallback() {
        val path = "/storage/v1/object/chat-media/synthetic/photo.png"
        assertTrue(StorageUploadPolicy.isUnsupportedRawRoute(404, "NOT_FOUND", path, path))
        assertFalse(StorageUploadPolicy.isUnsupportedRawRoute(404, "NOT_FOUND", null, path))
        assertFalse(StorageUploadPolicy.isUnsupportedRawRoute(404, "NOT_FOUND", "/other", path))
        assertFalse(StorageUploadPolicy.isUnsupportedRawRoute(404, "OBJECT_NOT_FOUND", path, path))
        assertTrue(StorageUploadPolicy.isUnsupportedRawRoute(405, "METHOD_NOT_ALLOWED", null, path))
        assertTrue(StorageUploadPolicy.isUnsupportedRawRoute(501, "NOT_IMPLEMENTED", null, path))
        for (status in listOf(400,401,403,408,409,413,415,422,429,500,502,503,504)) {
            assertFalse(StorageUploadPolicy.isUnsupportedRawRoute(status, "NOT_FOUND", path, path))
        }
    }
    @Test fun timeoutBudgetIncludesScanAndUploadMargin() {
        assertTrue(StorageUploadPolicy.RESPONSE_IDLE_TIMEOUT_MILLIS >= 180_000L + 60_000L)
        assertTrue(StorageUploadPolicy.REQUEST_TIMEOUT_MILLIS >= 15 * 60_000L + StorageUploadPolicy.RESPONSE_IDLE_TIMEOUT_MILLIS)
        assertEquals(30_000L, StorageUploadPolicy.CONNECT_TIMEOUT_MILLIS)
    }
}
