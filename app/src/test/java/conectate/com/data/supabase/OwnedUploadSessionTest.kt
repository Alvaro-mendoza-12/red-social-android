package conectate.com.data.supabase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OwnedUploadSessionTest {
    @Test fun credentialsAreRequestedForTheCapturedOwner() = runTest {
        val guard = AuthSessionMutationGuard()
        var requested: String? = null
        val session = OwnedUploadSession(guard.captureAccountLease { "a" }, "a") {
            requested = it; "a-token"
        }
        assertEquals("a-token", session.accessToken())
        assertEquals("a", requested)
        assertEquals("a", session.accountId)
    }

    @Test fun aDifferentExpectedOwnerCannotStartAnUpload() {
        val guard = AuthSessionMutationGuard()
        assertTrue(runCatching {
            OwnedUploadSession(guard.captureAccountLease { "b" }, "a") { fail("Must not request B credentials"); null }
        }.exceptionOrNull() is CancellationException)
    }

    @Test fun aTokenArrivingAfterAccountSwitchCannotAuthorizeOrRelabelTheUpload() = runTest {
        val guard = AuthSessionMutationGuard()
        var owner = "a"
        val response = CompletableDeferred<String>()
        val session = OwnedUploadSession(guard.captureAccountLease { owner }, owner) { response.await() }
        val task = async { session.accessToken() }
        runCurrent()
        guard.invalidate { owner = "b" }
        response.complete("b-token")
        assertTrue(runCatching { task.await() }.exceptionOrNull() is CancellationException)
        assertEquals("a", session.accountId)
    }

    @Test fun aNewLoginOfTheSameOwnerInvalidatesAnOldUpload() = runTest {
        val guard = AuthSessionMutationGuard()
        val session = OwnedUploadSession(guard.captureAccountLease { "a" }, null) { "token" }
        guard.invalidate {}
        assertTrue(runCatching { session.accessToken() }.exceptionOrNull() is CancellationException)
    }

    @Test fun backgroundTokenRenewalDoesNotInterruptTheSameDeviceSession() = runTest {
        val guard = AuthSessionMutationGuard()
        var token = "old"
        val session = OwnedUploadSession(guard.captureAccountLease { "a" }, null) { token }
        guard.commitIfCurrent(guard.snapshot()) { token = "renewed" }
        assertEquals("renewed", session.accessToken())
        session.requireCurrent()
    }

    @Test fun anOldProgressCallbackCannotUpdateTheNextSession() {
        val guard = AuthSessionMutationGuard()
        var owner = "a"
        val session = OwnedUploadSession(guard.captureAccountLease { owner }, owner) { "token" }
        val values = mutableListOf<Float>()
        val progress = session.progress { values += it }
        progress(0.25f)
        guard.invalidate { owner = "b" }
        val error = runCatching { progress(0.75f) }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertTrue(error?.cause is CancellationException)
        assertEquals(listOf(0.25f), values)
    }

    @Test fun aMissingTokenInTheCurrentSessionIsARetryableFailure() = runTest {
        val guard = AuthSessionMutationGuard()
        val session = OwnedUploadSession(guard.captureAccountLease { "a" }, null) { null }
        val error = runCatching { session.accessToken() }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(error is CancellationException)
    }

    @Test fun anAnonymousEntryCannotBecomeAnUploadForALaterLogin() {
        val guard = AuthSessionMutationGuard()
        var owner: String? = null
        val lease = guard.captureAccountLease { owner }
        guard.invalidate { owner = "b" }
        assertTrue(runCatching { OwnedUploadSession(lease, null) { "b-token" } }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun cancellationFromAProgressConsumerIsSafeForTheTransportWriterThread() {
        val guard = AuthSessionMutationGuard()
        val session = OwnedUploadSession(guard.captureAccountLease { "a" }, null) { "token" }
        val callback = session.progress { throw CancellationException("composer left") }
        val error = runCatching { callback(0.5f) }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertTrue(error?.cause is CancellationException)
    }

    @Test fun anOrdinaryTransportFailureCanUseTheFallbackForTheSameAccount() = runTest {
        val guard = AuthSessionMutationGuard()
        val requested = mutableListOf<String>()
        val session = OwnedUploadSession(guard.captureAccountLease { "a" }, null) { requested += it; "a-token" }
        val result = session.uploadWithFallback(primary = {
            assertEquals("a-token", session.accessToken())
            Result.failure<String>(java.io.IOException("bucket unavailable"))
        }, fallback = {
            assertEquals("a-token", session.accessToken())
            Result.success("stored-url")
        })
        assertEquals("stored-url", result.getOrThrow())
        assertEquals(listOf("a", "a"), requested)
    }

    @Test fun aWrappedLegacyCancellationCannotStartTheFallback() = runTest {
        val guard = AuthSessionMutationGuard()
        val session = OwnedUploadSession(guard.captureAccountLease { "a" }, null) { "token" }
        var fallbackCalls = 0
        val error = runCatching { session.uploadWithFallback(
            primary = { Result.failure<String>(CancellationException("screen left")) },
            fallback = { fallbackCalls++; Result.success("wrong") },
        ) }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(0, fallbackCalls)
    }

    @Test fun aFailedUploadAfterSwitchingAccountsCannotRetryWithTheNextAccountsCredentials() = runTest {
        val guard = AuthSessionMutationGuard()
        var owner = "a"
        val session = OwnedUploadSession(guard.captureAccountLease { owner }, null) { "$it-token" }
        val reply = CompletableDeferred<Result<String>>()
        var fallbackCalls = 0
        val send = async { session.uploadWithFallback(primary = { reply.await() },
            fallback = { fallbackCalls++; session.accessToken(); Result.success("wrong") }) }
        runCurrent()
        guard.invalidate { owner = "b" }
        reply.complete(Result.failure(java.io.IOException("old failure")))
        assertTrue(runCatching { send.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, fallbackCalls)
    }

    @Test fun aSuccessfulUploadAfterNewLoginOfTheSameOwnerCannotPublishItsOldResult() = runTest {
        val guard = AuthSessionMutationGuard()
        val session = OwnedUploadSession(guard.captureAccountLease { "a" }, null) { "token" }
        val reply = CompletableDeferred<Result<String>>()
        val send = async { session.uploadWithFallback(primary = { reply.await() }) }
        runCurrent(); guard.invalidate {}
        reply.complete(Result.success("old-url"))
        assertTrue(runCatching { send.await() }.exceptionOrNull() is CancellationException)
    }

    @Test fun aSwitchDuringFallbackCannotDeliverItsResultToAnotherSession() = runTest {
        val guard = AuthSessionMutationGuard()
        var owner = "a"
        val session = OwnedUploadSession(guard.captureAccountLease { owner }, null) { "token" }
        val reply = CompletableDeferred<Result<String>>()
        val send = async { session.uploadWithFallback(primary = { Result.failure(java.io.IOException("retry")) },
            fallback = { reply.await() }) }
        runCurrent(); guard.invalidate { owner = "b" }
        reply.complete(Result.success("old-url"))
        assertTrue(runCatching { send.await() }.exceptionOrNull() is CancellationException)
    }

    @Test fun aSuccessfulPrimaryNeverStartsAnotherUpload() = runTest {
        val guard = AuthSessionMutationGuard()
        val session = OwnedUploadSession(guard.captureAccountLease { "a" }, null) { "token" }
        val result = session.uploadWithFallback(primary = { Result.success("original") },
            fallback = { fail("No fallback after ACK"); Result.success("extra") })
        assertEquals("original", result.getOrThrow())
    }
}
