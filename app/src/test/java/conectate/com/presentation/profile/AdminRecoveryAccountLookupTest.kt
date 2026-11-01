package conectate.com.presentation.profile

import conectate.com.data.localfallback.AdminRecoveryAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AdminRecoveryAccountLookupTest {
    private fun account(id: String) = AdminRecoveryAccount(id, "$id@example.test", id, id)

    @Test fun aLateLookupCannotReplaceTheCurrentQueryResults() = runTest {
        val old = CompletableDeferred<Result<List<AdminRecoveryAccount>>>()
        var visible = emptyList<AdminRecoveryAccount>()
        val lookup = AdminRecoveryAccountLookup({ backgroundScope }, { true }, {
            if (it == "first") withContext(NonCancellable) { old.await() } else Result.success(listOf(account("second")))
        }, { visible = it }, { fail("Unexpected current query error") })
        lookup.search("first"); runCurrent()
        lookup.search("second"); runCurrent()
        old.complete(Result.success(listOf(account("first")))); runCurrent()
        assertEquals(listOf(account("second")), visible)
    }

    @Test fun closingOrChangingTheRecoveryCaseKeepsItsOldLookupCleared() = runTest {
        val old = CompletableDeferred<Result<List<AdminRecoveryAccount>>>()
        var visible = emptyList<AdminRecoveryAccount>()
        val lookup = AdminRecoveryAccountLookup({ backgroundScope }, { true }, {
            withContext(NonCancellable) { old.await() }
        }, { visible = it }, { fail("Obsolete error must not publish") })
        lookup.search("first"); runCurrent()
        lookup.clear()
        old.complete(Result.success(listOf(account("obsolete")))); runCurrent()
        assertTrue(visible.isEmpty())
    }

    @Test fun anInvalidSessionCannotReceiveOrStartARecoveryLookup() = runTest {
        var current = true
        var requests = 0
        val result = CompletableDeferred<Result<List<AdminRecoveryAccount>>>()
        var visible = emptyList<AdminRecoveryAccount>()
        val lookup = AdminRecoveryAccountLookup({ backgroundScope }, { current }, {
            requests++; result.await()
        }, { visible = it }, { fail("An obsolete session must not publish errors") })
        lookup.search("first"); runCurrent()
        current = false
        result.complete(Result.success(listOf(account("private")))); runCurrent()
        lookup.search("second"); runCurrent()
        assertEquals(1, requests)
        assertTrue(visible.isEmpty())
    }

    @Test fun aTimedOutOrFailedLookupCanBeRetriedWithoutReturningOldMatches() = runTest {
        var online = false
        var visible = listOf(account("old"))
        val errors = mutableListOf<Throwable>()
        val lookup = AdminRecoveryAccountLookup({ backgroundScope }, { true }, {
            if (online) Result.success(listOf(account("new"))) else awaitCancellation()
        }, { visible = it }, { errors += it }, timeoutMillis = 100)
        lookup.search("query"); runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(1, errors.size)
        assertTrue(visible.isEmpty())
        online = true; lookup.search("query"); runCurrent()
        assertEquals(listOf(account("new")), visible)
        lookup.search("a"); runCurrent()
        assertTrue(visible.isEmpty())
    }
}
