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
}
