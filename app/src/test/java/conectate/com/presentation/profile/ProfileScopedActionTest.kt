package conectate.com.presentation.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ProfileScopedActionTest {
    @Test fun lateResultCannotChangeAnotherProfile() = runBlocking {
        val scope = ProfileFriendActionScope { "owner" }
        scope.enter("first")
        val ticket = requireNotNull(scope.snapshot())
        val response = CompletableDeferred<Boolean>()
        var started = false
        var applied = false
        val job = launch { scope.runCurrentAction(ticket, { started = true }, { response.await() }, { applied = true }) }
        yield()
        assertTrue(started)
        scope.enter("second")
        response.complete(false)
        job.join()
        assertFalse(applied)
    }

    @Test fun accountChangeOrSignOutDiscardsResult() = runBlocking {
        for (next in listOf("other-owner", null)) {
            var owner: String? = "owner"
            val scope = ProfileFriendActionScope { owner }
            scope.enter("same-profile")
            val ticket = requireNotNull(scope.snapshot())
            val response = CompletableDeferred<Boolean>()
            var applied = false
            val job = launch { scope.runCurrentAction(ticket, {}, { response.await() }, { applied = true }) }
            yield()
            owner = next
            response.complete(false)
            job.join()
            assertFalse(applied)
        }
    }

    @Test fun staleQueuedActionDoesNotEvenStart() = runBlocking {
        val scope = ProfileFriendActionScope { "owner" }
        scope.enter("first")
        val ticket = requireNotNull(scope.snapshot())
        scope.enter("second")
        scope.runCurrentAction(ticket, { fail("optimistic update") }, { fail("network request") }, { fail("result") })
    }

    @Test fun currentViewReceivesItsResult() = runBlocking {
        val scope = ProfileFriendActionScope { "owner" }
        scope.enter("first")
        val ticket = requireNotNull(scope.snapshot())
        val events = mutableListOf<String>()
        scope.runCurrentAction(ticket, { events += "start" }, { "failure" }, { events += it })
        assertEquals(listOf("start", "failure"), events)
    }
    @Test fun refreshingSameRouteKeepsMutationButNavigationBackDoesNot() {
        val scope = ProfileFriendActionScope { "owner" }
        scope.enter("local-alias")
        val ticket = requireNotNull(scope.snapshot())
        scope.enter("local-alias")
        assertTrue(scope.isCurrent(ticket))
        scope.enter("other-profile")
        scope.enter("local-alias")
        assertFalse(scope.isCurrent(ticket))
    }

    @Test fun sameProfileUnderAnotherAccountGetsNewContext() {
        var owner: String? = "first-owner"
        val scope = ProfileFriendActionScope { owner }
        scope.enter("profile-alias")
        val old = requireNotNull(scope.snapshot())
        owner = "second-owner"
        scope.enter("profile-alias")
        assertFalse(scope.isCurrent(old))
        assertTrue(scope.isCurrent(requireNotNull(scope.snapshot())))
    }
    @Test fun signOutAndReturnCannotReviveAnOldTicket() {
        val scope = ProfileFriendActionScope { "owner" }
        scope.enter("same-route")
        val old = requireNotNull(scope.snapshot())
        scope.invalidate()
        scope.enter("same-route")
        assertFalse(scope.isCurrent(old))
        assertTrue(scope.isCurrent(requireNotNull(scope.snapshot())))
    }
}
