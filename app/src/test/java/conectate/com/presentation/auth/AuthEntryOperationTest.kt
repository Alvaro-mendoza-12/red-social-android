package conectate.com.presentation.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AuthEntryOperationTest {
    @Test fun sessionHydrationExceptionBecomesARecoverableFormError() = runTest {
        val fault = IllegalStateException("profile hydration failed")
        assertSame(fault, authEntryOperation<Unit> { throw fault }.exceptionOrNull())
    }
    @Test fun serverFailureAndSuccessKeepTheirOriginalMeaning() = runTest {
        val denied = java.io.IOException("offline")
        assertSame(denied, authEntryOperation<Unit> { Result.failure(denied) }.exceptionOrNull())
        assertEquals("session", authEntryOperation { Result.success("session") }.getOrThrow())
    }
    @Test fun resultWrappedCancellationDoesNotBecomeAnAuthFailure() = runTest {
        var cancelled = false
        try { authEntryOperation<Unit> { Result.failure(CancellationException("left")) } }
        catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test fun leavingTheFormCancelsTheOperationWithoutPublishingAFailure() = runTest {
        val started = CompletableDeferred<Unit>()
        var published = false
        val job = launch {
            authEntryOperation<Unit> { started.complete(Unit); awaitCancellation() }
            published = true
        }
        started.await(); job.cancel(); job.join()
        assertFalse(published)
    }
}
