package conectate.com.data.repository

import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationAccountCallTest {
    @Test fun `a renewed credential cannot authorize an earlier account generation`() = runTest {
        val guard = AuthSessionMutationGuard()
        var account = "a"
        val lease = guard.captureAccountLease { account }
        val credential = CompletableDeferred<String?>()
        var sent = false
        val request = async {
            notificationAccountCall(lease, { owner ->
                assertEquals("a", owner)
                withContext(NonCancellable) { credential.await() }
            }) { _, _ -> sent = true; Result.success(Unit) }
        }
        runCurrent()
        guard.invalidate { account = "b" }
        guard.invalidate { account = "a" }
        credential.complete("a-new-session-token")
        try { request.await(); fail("A stale generation must not send") }
        catch (_: CancellationException) { }
        assertFalse(sent)
    }

    @Test fun `a delayed successful response cannot return after changing account`() = runTest {
        val guard = AuthSessionMutationGuard()
        var account = "a"
        val lease = guard.captureAccountLease { account }
        val response = CompletableDeferred<Result<Int>>()
        val request = async {
            notificationAccountCall(lease, { "token-a" }) { _, _ ->
                withContext(NonCancellable) { response.await() }
            }
        }
        runCurrent()
        guard.invalidate { account = "b" }
        response.complete(Result.success(9))
        try { request.await(); fail("A response for a different account must not return") }
        catch (_: CancellationException) { }
    }

    @Test fun `the backend receives the captured owner and its token`() = runTest {
        val guard = AuthSessionMutationGuard()
        val lease = guard.captureAccountLease { "A" }
        val result = notificationAccountCall(lease, { owner ->
            assertEquals("a", owner)
            "token-for-$owner"
        }) { owner, token ->
            assertEquals("a", owner)
            assertEquals("token-for-a", token)
            Result.success(4)
        }
        assertEquals(4, result.getOrThrow())
    }

    @Test fun `a missing account or credential never sends a request`() = runTest {
        val guard = AuthSessionMutationGuard()
        var requestedToken = false
        var sent = false
        val missingOwner = notificationAccountCall(guard.captureAccountLease { null }, {
            requestedToken = true; "irrelevant"
        }) { _, _ -> sent = true; Result.success(Unit) }
        val missingToken = notificationAccountCall(guard.captureAccountLease { "a" }, { null }) { _, _ ->
            sent = true; Result.success(Unit)
        }
        assertTrue(missingOwner.isFailure)
        assertTrue(missingToken.isFailure)
        assertFalse(requestedToken)
        assertFalse(sent)
    }

    @Test fun `Result wrapped cancellation propagates instead of becoming a retryable failure`() = runTest {
        val lease = AuthSessionMutationGuard().captureAccountLease { "a" }
        try {
            notificationAccountCall<Unit>(lease, { "token" }) { _, _ ->
                Result.failure(CancellationException("screen left"))
            }
            fail("Cancellation must propagate")
        } catch (expected: CancellationException) {
            assertEquals("screen left", expected.message)
        }
    }

    @Test fun `a current transport failure remains available to the retry UI`() = runTest {
        val lease = AuthSessionMutationGuard().captureAccountLease { "a" }
        val result = notificationAccountCall<Unit>(lease, { "token" }) { _, _ ->
            throw IOException("connection interrupted")
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("connection interrupted", result.exceptionOrNull()?.message)
    }

    @Test fun `an obsolete credential failure is cancelled rather than shown on another account`() = runTest {
        val guard = AuthSessionMutationGuard()
        var account = "a"
        val lease = guard.captureAccountLease { account }
        try {
            notificationAccountCall<Unit>(lease, {
                guard.invalidate { account = "b" }
                throw IOException("old refresh failed")
            }) { _, _ -> fail("Must not send"); Result.success(Unit) }
            fail("An obsolete failure must not return")
        } catch (_: CancellationException) { }
    }
}
