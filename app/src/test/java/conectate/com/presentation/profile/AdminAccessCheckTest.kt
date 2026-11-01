package conectate.com.presentation.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AdminAccessCheckTest {
    @Test fun aSuccessfulLookupWithFalseDoesNotGrantAccess() = runTest {
        val decisions = mutableListOf<AdminAccessDecision>()
        val check = AdminAccessCheck({ backgroundScope }, { "a" }, { Result.success(false) }, { decisions += it })
        check.refresh(); runCurrent()
        assertEquals(AdminAccessDecision(granted = false, unavailable = false), decisions.last())
        assertFalse(decisions.any { it.granted })
    }

    @Test fun onlyAConfirmedTrueCanGrantAccess() = runTest {
        val ack = CompletableDeferred<Result<Boolean>>()
        val decisions = mutableListOf<AdminAccessDecision>()
        val check = AdminAccessCheck({ backgroundScope }, { "a" }, { ack.await() }, { decisions += it })
        check.refresh(); runCurrent()
        assertFalse(decisions.last().granted)
        ack.complete(Result.success(true)); runCurrent()
        assertTrue(decisions.last().granted)
    }

    @Test fun failureDoesNotGrantAndCanBeRetried() = runTest {
        var online = false
        val decisions = mutableListOf<AdminAccessDecision>()
        val check = AdminAccessCheck({ backgroundScope }, { "a" }, {
            if (online) Result.success(true) else throw java.io.IOException("offline")
        }, { decisions += it })
        check.refresh(); runCurrent()
        assertEquals(AdminAccessDecision(unavailable = true), decisions.last())
        online = true
        check.refresh(); runCurrent()
        assertTrue(decisions.last().granted)
    }

    @Test fun anOldGrantCannotOverrideANewerRevocation() = runTest {
        val old = CompletableDeferred<Result<Boolean>>()
        var requests = 0
        val decisions = mutableListOf<AdminAccessDecision>()
        val check = AdminAccessCheck({ backgroundScope }, { "a" }, {
            if (++requests == 1) withContext(NonCancellable) { old.await() } else Result.success(false)
        }, { decisions += it })
        check.refresh(); runCurrent()
        check.refresh(); runCurrent()
        old.complete(Result.success(true)); runCurrent()
        assertFalse(decisions.any { it.granted })
        assertFalse(decisions.last().unavailable)
    }

    @Test fun aLateGrantCannotAuthorizeAnotherAccount() = runTest {
        var owner = "a"
        val old = CompletableDeferred<Result<Boolean>>()
        val decisions = mutableListOf<AdminAccessDecision>()
        val check = AdminAccessCheck({ backgroundScope }, { owner }, { old.await() }, { decisions += it })
        check.refresh(); runCurrent()
        owner = "b"
        old.complete(Result.success(true)); runCurrent()
        assertEquals(listOf(AdminAccessDecision()), decisions)
    }

    @Test fun timeoutAndCancellationDoNotBecomePermissionGrants() = runTest {
        val decisions = mutableListOf<AdminAccessDecision>()
        val check = AdminAccessCheck({ backgroundScope }, { "a" }, { awaitCancellation() }, { decisions += it }, timeoutMillis = 100)
        check.refresh(); runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(AdminAccessDecision(unavailable = true), decisions.last())
        decisions.clear()
        val leaving = AdminAccessCheck({ backgroundScope }, { "a" }, { Result.failure(CancellationException("leaving")) }, { decisions += it })
        leaving.refresh(); runCurrent()
        assertEquals(listOf(AdminAccessDecision()), decisions)
    }
}
