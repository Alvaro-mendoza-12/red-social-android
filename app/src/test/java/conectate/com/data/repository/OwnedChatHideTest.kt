package conectate.com.data.repository

import conectate.com.data.auth.AccountLocalStorageGate
import kotlinx.coroutines.sync.Mutex
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OwnedChatHideTest {
    @Test fun tokenFinishingAfterAccountSwitchCannotSend() = runBlocking {
        var account = "a"
        val guard = AuthSessionMutationGuard()
        val lease = guard.captureAccountLease { account }
        val token = CompletableDeferred<String>()
        var sends = 0; var commits = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            executeOwnedChatHide(lease, { token.await() }, { sends++; Result.success(Unit) }, null, { commits++ })
        }
        guard.invalidate { account = "b" }; token.complete("token-b")
        try { pending.await(); fail("stale operation must cancel") } catch (_: CancellationException) { }
        assertEquals(0, sends); assertEquals(0, commits)
    }

    @Test fun lateRemoteSuccessCannotHideTheNewAccountsRowIncludingReturnToA() = runBlocking {
        var account = "a"
        val guard = AuthSessionMutationGuard()
        val lease = guard.captureAccountLease { account }
        val response = CompletableDeferred<Result<Unit>>()
        var commits = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            executeOwnedChatHide(lease, { "token-a" }, { response.await() }, null, { commits++ })
        }
        guard.invalidate { account = "b" }; guard.invalidate { account = "a" }
        response.complete(Result.success(Unit))
        try { pending.await(); fail("stale operation must cancel") } catch (_: CancellationException) { }
        assertEquals(0, commits)
    }

    @Test fun lateFailureDoesNotLaunchFallbackForAnotherAccount() = runBlocking {
        var account = "a"
        val guard = AuthSessionMutationGuard()
        val lease = guard.captureAccountLease { account }
        val response = CompletableDeferred<Result<Unit>>()
        var clears = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            executeOwnedChatHide(lease, { "token-a" }, { response.await() }, { clears++; Result.success(Unit) }, {})
        }
        guard.invalidate { account = "b" }; response.complete(Result.failure(Exception("offline")))
        try { pending.await(); fail("stale operation must cancel") } catch (_: CancellationException) { }
        assertEquals(0, clears)
    }

    @Test fun returnedCancellationNeverFallsBackOrCommits() = runBlocking {
        val lease = AuthSessionMutationGuard().captureAccountLease { "a" }
        var clears = 0; var commits = 0
        try {
            executeOwnedChatHide(lease, { "token" }, { Result.failure(CancellationException("cancel")) },
                { clears++; Result.success(Unit) }, { commits++ })
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(0, clears); assertEquals(0, commits)
    }

    @Test fun currentFallbackUsesTheSameTokenAndCommitsOnlyAfterSuccess() = runBlocking {
        val lease = AuthSessionMutationGuard().captureAccountLease { "a" }
        val events = mutableListOf<String>()
        val result = executeOwnedChatHide(lease, { "token-a" },
            { events += "hide:$it"; Result.failure(Exception("unsupported")) },
            { events += "clear:$it"; Result.success(Unit) }, { events += "commit" })
        assertTrue(result.isSuccess)
        assertEquals(listOf("hide:token-a", "clear:token-a", "commit"), events)
    }

    @Test fun failedRemoteWithoutFallbackDoesNotCommit() = runBlocking {
        val lease = AuthSessionMutationGuard().captureAccountLease { "a" }
        var commits = 0
        val error = Exception("denied")
        val result = executeOwnedChatHide(lease, { "token" }, { Result.failure(error) }, null, { commits++ })
        assertSame(error, result.exceptionOrNull()); assertEquals(0, commits)
    }
    @Test fun accountChangingWhileWaitingForLocalStorageCannotCommit() = runBlocking {
        var account = "a"
        val guard = AuthSessionMutationGuard()
        val lease = guard.captureAccountLease { account }
        val activation = Mutex(locked = true)
        val gate = AccountLocalStorageGate(activation, guard)
        val reachedCommit = CompletableDeferred<Unit>()
        var writes = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            executeOwnedChatHide(lease, { "token" }, { Result.success(Unit) }, null, {
                reachedCommit.complete(Unit)
                gate.withOwner(lease) { writes++ }
            })
        }
        reachedCommit.await()
        guard.invalidate { account = "b" }; activation.unlock()
        try { pending.await(); fail("stale storage commit must cancel") } catch (_: CancellationException) { }
        assertEquals(0, writes)
    }

    @Test fun cancelledCoroutineCannotFallbackEvenIfTransportReturnsFailure() = runBlocking {
        val lease = AuthSessionMutationGuard().captureAccountLease { "a" }
        val entered = CompletableDeferred<Unit>()
        var clears = 0; var writes = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            executeOwnedChatHide(lease, { "token" }, {
                entered.complete(Unit)
                try { awaitCancellation() } catch (error: CancellationException) { Result.failure(Exception("wrapped", error)) }
            }, { clears++; Result.success(Unit) }, { writes++ })
        }
        entered.await(); pending.cancel()
        try { pending.await(); fail("cancelled operation must cancel") } catch (_: CancellationException) { }
        assertEquals(0, clears); assertEquals(0, writes)
    }
    @Test fun cancellationRollsBackCurrentOptimismWithoutAnErrorMessage() = runBlocking {
        val guard = AuthSessionMutationGuard()
        val lease = guard.captureAccountLease { "a" }
        var hidden = true
        var message: String? = null
        try {
            runChatHideWithRollback({ throw CancellationException("cancel") }) { error ->
                guard.commitForLease(lease) { hidden = false; message = error?.message }
            }
            fail("must propagate cancellation")
        } catch (_: CancellationException) { }
        assertFalse(hidden); assertNull(message)
    }

    @Test fun cancellationCannotRollbackTheNewAccountsOptimism() = runBlocking {
        var account = "a"
        val guard = AuthSessionMutationGuard()
        val lease = guard.captureAccountLease { account }
        var hidden = true
        var message = "new-account-message"
        try {
            runChatHideWithRollback({
                guard.invalidate { account = "b" }
                throw CancellationException("old cancellation")
            }) { error ->
                guard.commitForLease(lease) { hidden = false; message = error?.message.orEmpty() }
            }
            fail("must propagate cancellation")
        } catch (_: CancellationException) { }
        assertTrue(hidden); assertEquals("new-account-message", message)
    }
    @Test fun returnedCancellationAlsoRollsBackWithoutReportingFailure() = runBlocking {
        val errors = mutableListOf<Throwable?>()
        try {
            runChatHideWithRollback({ Result.failure(CancellationException("cancel")) }) { errors += it }
            fail("must propagate cancellation")
        } catch (_: CancellationException) { }
        assertEquals(listOf<Throwable?>(null), errors)
    }

    @Test fun ordinaryFailureRollsBackButSuccessDoesNot() = runBlocking {
        val errors = mutableListOf<Throwable?>()
        val error = Exception("denied")
        runChatHideWithRollback({ Result.success(Unit) }) { errors += it }
        assertTrue(errors.isEmpty())
        runChatHideWithRollback({ Result.failure(error) }) { errors += it }
        assertEquals(listOf(error), errors)
    }
}
