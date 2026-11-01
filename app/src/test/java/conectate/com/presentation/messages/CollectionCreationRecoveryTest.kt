package conectate.com.presentation.messages

import conectate.com.data.repository.collection.CollectionCreationRequest
import conectate.com.data.supabase.AuthSessionMutationGuard
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionCreationRecoveryTest {
    private fun request() = CollectionCreationRequest("id", "a", "group", "Original", null, null, createdAt = "2026-11-01T12:00:00Z")

    @Test fun aStoredRequestKeepsTheFreshFormHiddenUntilItIsResolved() = runTest {
        val session = CollectionCreationSession(backgroundScope, AuthSessionMutationGuard().captureAccountLease { "a" }, {})
        val pending = request()
        val recovery = CollectionCreationRecovery(session, { pending }, {})
        assertTrue(recovery.state.value.loading)
        recovery.refresh()
        assertEquals(pending, recovery.state.value.pending)
        assertTrue(recovery.state.value.showRecovery)
    }

    @Test fun readFailureBlocksANewCreationAndCanBeRetried() = runTest {
        val session = CollectionCreationSession(backgroundScope, AuthSessionMutationGuard().captureAccountLease { "a" }, {})
        var fail = true
        val errors = mutableListOf<String>()
        val recovery = CollectionCreationRecovery(session, { if (fail) throw IOException("disk read"); null }, errors::add)
        recovery.refresh()
        assertTrue(recovery.state.value.showRecovery)
        assertTrue(recovery.state.value.readFailed)
        assertFalse(recovery.state.value.loading)
        assertEquals(1, errors.size)
        fail = false
        recovery.refresh()
        assertFalse(recovery.state.value.showRecovery)
    }

    @Test fun anOlderReadCannotRestoreAnIntentAfterTheLatestReadClearedIt() = runTest {
        val session = CollectionCreationSession(backgroundScope, AuthSessionMutationGuard().captureAccountLease { "a" }, {})
        val slow = CompletableDeferred<CollectionCreationRequest?>()
        var reads = 0
        val recovery = CollectionCreationRecovery(session, { if (++reads == 1) slow.await() else null }, {})
        val old = launch { recovery.refresh() }; runCurrent()
        recovery.refresh()
        slow.complete(request()); old.join()
        assertFalse(recovery.state.value.showRecovery)
    }

    @Test fun aLatePrivateRequestCannotBePublishedAfterAnAccountSwitch() = runTest {
        val guard = AuthSessionMutationGuard()
        var owner = "a"
        val session = CollectionCreationSession(backgroundScope, guard.captureAccountLease { owner }, {})
        val slow = CompletableDeferred<CollectionCreationRequest?>()
        val recovery = CollectionCreationRecovery(session, { withContext(NonCancellable) { slow.await() } }, {})
        val old = backgroundScope.launch { recovery.refresh() }; runCurrent()
        guard.invalidate { owner = "b" }; session.sessionChanged()
        slow.complete(request()); runCurrent()
        assertNull(recovery.state.value.pending)
        assertTrue(old.isCancelled)
    }
}
