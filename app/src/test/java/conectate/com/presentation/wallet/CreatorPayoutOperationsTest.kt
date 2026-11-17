package conectate.com.presentation.wallet

import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CreatorPayoutOperationsTest {
    @Test fun repeatedClicksBeforeDispatchRunOnlyOneCommand() = runTest {
        val guard = AuthSessionMutationGuard()
        val states = mutableListOf<Boolean>()
        val errors = mutableListOf<Throwable>()
        val operations = CreatorPayoutOperations(backgroundScope, { guard.captureAccountLease { "a" } }, states::add, errors::add)
        var sent = 0
        val response = CompletableDeferred<Unit>()
        operations.run { sent++; response.await() }
        operations.run { sent++; response.await() }
        assertEquals(listOf(true), states)
        runCurrent(); assertEquals(1, sent)
        response.complete(Unit); runCurrent()
        assertEquals(listOf(true, false), states)
        assertTrue(errors.isEmpty())
    }

    @Test fun changingAccountBeforeDispatchDoesNotRunTheCommand() = runTest {
        val guard = AuthSessionMutationGuard()
        var account = "a"
        var busy = false
        var sent = false
        val operations = CreatorPayoutOperations(backgroundScope, { guard.captureAccountLease { account } }, { busy = it }, { fail(it.toString()) })
        operations.run { sent = true }
        guard.invalidate { account = "b" }; runCurrent()
        assertFalse(sent)
        assertFalse(busy)
    }

    @Test fun aNewVisitToTheSameAccountDoesNotRunItsOldQueuedCommand() = runTest {
        val guard = AuthSessionMutationGuard()
        var sent = false
        val operations = CreatorPayoutOperations(backgroundScope, { guard.captureAccountLease { "a" } }, {}, { fail(it.toString()) })
        operations.run { sent = true }
        guard.invalidate { }; runCurrent()
        assertFalse(sent)
    }

    @Test fun transportFailureReleasesTheControlAndAllowsRetry() = runTest {
        val guard = AuthSessionMutationGuard()
        var busy = false
        val errors = mutableListOf<Throwable>()
        val operations = CreatorPayoutOperations(backgroundScope, { guard.captureAccountLease { "a" } }, { busy = it }, errors::add)
        operations.run { throw IOException("offline") }; runCurrent()
        assertFalse(busy)
        assertEquals("offline", errors.single().message)
        var retried = false
        operations.run { retried = true }; runCurrent()
        assertTrue(retried)
        assertFalse(busy)
    }

    @Test fun cancellationReleasesTheControlWithoutShowingAnError() = runTest {
        val guard = AuthSessionMutationGuard()
        var busy = false
        val operations = CreatorPayoutOperations(backgroundScope, { guard.captureAccountLease { "a" } }, { busy = it }, { fail(it.toString()) })
        operations.run { throw CancellationException("interrupted") }; runCurrent()
        assertFalse(busy)
    }

    @Test fun anOldNonCancellableCompletionDoesNotUnlockTheNewOperation() = runTest {
        val guard = AuthSessionMutationGuard()
        var account = "a"
        var busy = false
        val operations = CreatorPayoutOperations(backgroundScope, { guard.captureAccountLease { account } }, { busy = it }, { fail(it.toString()) })
        val old = CompletableDeferred<Unit>()
        val current = CompletableDeferred<Unit>()
        operations.run { withContext(NonCancellable) { old.await() } }; runCurrent()
        guard.invalidate { account = "b" }
        operations.run { current.await() }; runCurrent()
        old.complete(Unit); runCurrent()
        assertTrue(busy)
        current.complete(Unit); runCurrent()
        assertFalse(busy)
    }

    @Test fun anOldFailureIsNotShownInTheNewVisit() = runTest {
        val guard = AuthSessionMutationGuard()
        val errors = mutableListOf<Throwable>()
        val operations = CreatorPayoutOperations(backgroundScope, { guard.captureAccountLease { "a" } }, {}, errors::add)
        val response = CompletableDeferred<Unit>()
        operations.run { withContext(NonCancellable) { response.await() }; throw IOException("old") }
        runCurrent(); guard.invalidate { }; response.complete(Unit); runCurrent()
        assertTrue(errors.isEmpty())
    }
}
