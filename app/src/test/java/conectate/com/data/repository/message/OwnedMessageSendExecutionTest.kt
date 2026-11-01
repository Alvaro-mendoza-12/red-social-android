package conectate.com.data.repository.message

import conectate.com.data.repository.SessionWriteGuard
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedMessageSendExecutionTest {
    private class Fixture {
        var owner: String? = "a"
        val auth = AuthSessionMutationGuard()
        val lease = auth.captureAccountLease { owner }
        val writes = SessionWriteGuard { owner }
    }

    @Test fun switchingBeforeIoCannotSelectTheNewSender() = runTest {
        val f = Fixture(); var delivered = false
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            executeOwnedMessageSend(f.lease, f.writes, ioDispatcher = StandardTestDispatcher(testScheduler)) { _, _ ->
                delivered = true; Result.success(Unit)
            }
        }
        f.auth.invalidate { f.owner = "b" }; runCurrent()
        assertTrue(runCatching { sending.await() }.exceptionOrNull() is CancellationException)
        assertFalse(delivered)
    }

    @Test fun signingInAgainAsTheSameAccountInvalidatesTheOldSend() = runTest {
        val f = Fixture(); var delivered = false
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            executeOwnedMessageSend(f.lease, f.writes, ioDispatcher = StandardTestDispatcher(testScheduler)) { _, _ ->
                delivered = true; Result.success(Unit)
            }
        }
        f.auth.invalidate {}; runCurrent()
        assertTrue(runCatching { sending.await() }.exceptionOrNull() is CancellationException)
        assertFalse(delivered)
    }

    @Test fun tokenRenewalPreservesTheOriginalSenderAndQueueCallback() = runTest {
        val f = Fixture(); var queued = 0
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            executeOwnedMessageSend(f.lease, f.writes, onQueued = { queued++ }, ioDispatcher = StandardTestDispatcher(testScheduler)) { snapshot, queue ->
                assertEquals("a", snapshot.accountId)
                assertSame(f.lease, snapshot.accountLease)
                queue(); Result.success("ack")
            }
        }
        f.auth.commitIfCurrent(f.auth.snapshot()) {}; runCurrent()
        assertEquals("ack", sending.await().getOrThrow())
        assertEquals(1, queued)
    }

    @Test fun pausingRepositoryWritesBeforeIoAlsoInvalidatesTheOperation() = runTest {
        val f = Fixture(); var delivered = false
        val sending = async(start = CoroutineStart.UNDISPATCHED) {
            executeOwnedMessageSend(f.lease, f.writes, ioDispatcher = StandardTestDispatcher(testScheduler)) { _, _ ->
                delivered = true; Result.success(Unit)
            }
        }
        f.writes.invalidate(); runCurrent()
        assertTrue(f.lease.isCurrent())
        assertTrue(runCatching { sending.await() }.exceptionOrNull() is CancellationException)
        assertFalse(delivered)
    }

    @Test fun anAccountChangeAfterTheQueueDoesNotAcknowledgeTheOldResult() = runTest {
        val f = Fixture(); var queued = 0
        val error = runCatching {
            executeOwnedMessageSend(f.lease, f.writes, onQueued = { queued++ }, ioDispatcher = UnconfinedTestDispatcher(testScheduler)) { _, queue ->
                queue(); f.auth.invalidate { f.owner = "b" }; Result.success("late ack")
            }
        }.exceptionOrNull()
        assertEquals(1, queued)
        assertTrue(error is CancellationException)
    }

    @Test fun anObsoleteQueueCallbackCannotNavigateTheNewAccount() = runTest {
        val f = Fixture(); var callback = false
        val error = runCatching {
            executeOwnedMessageSend(f.lease, f.writes, onQueued = { callback = true }, ioDispatcher = UnconfinedTestDispatcher(testScheduler)) { _, queue ->
                f.auth.invalidate { f.owner = "b" }; queue(); Result.success(Unit)
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertFalse(callback)
    }

    @Test fun cancellationInsideAResultIsNeverACompletedSend() = runTest {
        val f = Fixture()
        val error = runCatching {
            executeOwnedMessageSend<Unit>(f.lease, f.writes, ioDispatcher = UnconfinedTestDispatcher(testScheduler)) { _, _ ->
                Result.failure(CancellationException("client cancelled"))
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }

    @Test fun aClientIgnoringCancellationStillCannotDeliverItsQueuedUiCallback() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var callback = false
        val sending = async {
            executeOwnedMessageSend(f.lease, f.writes, onQueued = { callback = true }, ioDispatcher = StandardTestDispatcher(testScheduler)) { _, queue ->
                withContext(NonCancellable) { entered.complete(Unit); release.await(); queue() }
                Result.success(Unit)
            }
        }
        runCurrent(); entered.await(); sending.cancel(); release.complete(Unit); runCurrent()
        assertTrue(runCatching { sending.await() }.exceptionOrNull() is CancellationException)
        assertFalse(callback)
    }

    @Test fun anAnonymousAttemptFailsWithoutEnteringDelivery() = runTest {
        val f = Fixture(); f.auth.invalidate { f.owner = null }
        val anonymous = f.auth.captureAccountLease { f.owner }
        var delivered = false
        val result = executeOwnedMessageSend(anonymous, f.writes) { _, _ -> delivered = true; Result.success(Unit) }
        assertTrue(result.isFailure)
        assertEquals("Inicia sesión para enviar mensajes.", result.exceptionOrNull()?.message)
        assertFalse(delivered)
    }

    @Test fun anOrdinaryFailureIsReturnedForTheSameCurrentOperation() = runTest {
        val f = Fixture(); val failure = java.io.IOException("offline")
        val result = executeOwnedMessageSend<Unit>(f.lease, f.writes, ioDispatcher = UnconfinedTestDispatcher(testScheduler)) { _, _ -> Result.failure(failure) }
        assertSame(failure, result.exceptionOrNull())
    }
}
