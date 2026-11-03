package conectate.com.data.auth

import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountLocalStorageGateTest {
    @Test fun ordinaryWritesReturnTheirResultAndReleaseActivation() = runTest {
        val activation = Mutex()
        val sessions = AuthSessionMutationGuard()
        val gate = AccountLocalStorageGate(activation, sessions)
        assertEquals("stored", gate.withOwner(sessions.captureAccountLease { "a" }) { "stored" })
        assertFalse(activation.isLocked)
    }

    @Test fun foreignAndReadOnlyLeasesCannotAuthorizeLocalStorage() = runTest {
        val sessions = AuthSessionMutationGuard()
        val gate = AccountLocalStorageGate(Mutex(), sessions)
        val foreign = AuthSessionMutationGuard().captureAccountLease { "a" }
        val readOnly = AuthSessionLease("a") { true }
        var wrote = false
        for (lease in listOf(foreign, readOnly)) {
            assertTrue(runCatching { gate.withOwner(lease) { wrote = true } }.exceptionOrNull() is CancellationException)
        }
        assertFalse(wrote)
    }

    @Test fun returningToTheSameAccountDoesNotReviveAnOldWrite() = runTest {
        val sessions = AuthSessionMutationGuard()
        var account = "a"
        val old = sessions.captureAccountLease { account }
        val gate = AccountLocalStorageGate(Mutex(), sessions)
        sessions.invalidate { account = "b" }
        sessions.invalidate { account = "a" }
        var wrote = false
        assertTrue(runCatching { gate.withOwner(old) { wrote = true } }.exceptionOrNull() is CancellationException)
        assertFalse(wrote)
        gate.withOwner(sessions.captureAccountLease { account }) { wrote = true }
        assertTrue(wrote)
    }

    @Test fun aQueuedWriteRechecksItsOwnerAfterActivationFinishes() = runTest {
        val sessions = AuthSessionMutationGuard()
        var account = "a"
        val old = sessions.captureAccountLease { account }
        val activation = Mutex(locked = true)
        val gate = AccountLocalStorageGate(activation, sessions)
        var wrote = false
        val queued = async { runCatching { gate.withOwner(old) { wrote = true } } }
        runCurrent()
        assertFalse(queued.isCompleted)
        sessions.invalidate { account = "b" }
        activation.unlock()
        assertTrue(queued.await().exceptionOrNull() is CancellationException)
        assertFalse(wrote)
        assertFalse(activation.isLocked)
    }

    @Test fun cancellationWhileQueuedCannotWriteAfterTheMutexIsReleased() = runTest {
        val sessions = AuthSessionMutationGuard()
        val activation = Mutex(locked = true)
        val gate = AccountLocalStorageGate(activation, sessions)
        var wrote = false
        val queued = async { gate.withOwner(sessions.captureAccountLease { "a" }) { wrote = true } }
        runCurrent()
        queued.cancelAndJoin()
        activation.unlock()
        runCurrent()
        assertFalse(wrote)
    }

    @Test fun failureDoesNotKeepAnotherAccountWaitingForStorage() = runTest {
        val sessions = AuthSessionMutationGuard()
        val activation = Mutex()
        val gate = AccountLocalStorageGate(activation, sessions)
        val owner = sessions.captureAccountLease { "a" }
        assertTrue(runCatching { gate.withOwner(owner) { error("Storage unavailable") } }.isFailure)
        assertFalse(activation.isLocked)
        assertEquals(1, gate.withOwner(owner) { 1 })
    }
}
