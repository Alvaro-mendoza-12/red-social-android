package conectate.com.presentation.messages

import conectate.com.data.supabase.AuthSessionMutationGuard
import org.junit.Assert.*
import org.junit.Test

class OwnedForwardCoordinatorTest {
    private val source = ForwardMessagePayload("id", "sender", "private content", "text", null, 1, false)

    @Test fun changingAccountsHidesThePreviousPrivateForwardImmediately() {
        val auth = AuthSessionMutationGuard(); var account = "a"
        val coordinator = UniversalForwardCoordinator { auth.captureAccountLease { account } }
        coordinator.open(listOf(source), setOf(source.id))
        val oldId = coordinator.activeRequestId!!
        auth.invalidate { account = "b" }
        assertNull(coordinator.request); assertNull(coordinator.activeRequestId)
        assertEquals(ShareDismissal.IGNORED, coordinator.dismiss(oldId))
        coordinator.validateSession()
        coordinator.open(listOf(source.copy(content = "new account")), setOf(source.id))
        assertEquals("new account", coordinator.request?.candidates?.single()?.content)
    }

    @Test fun signingInAgainAsTheSameUserDoesNotRestoreTheOldSelection() {
        val auth = AuthSessionMutationGuard()
        val coordinator = UniversalForwardCoordinator { auth.captureAccountLease { "a" } }
        coordinator.open(listOf(source), setOf(source.id))
        auth.invalidate {}
        assertNull(coordinator.request)
    }

    @Test fun renewingTheTokenPreservesTheCurrentSelection() {
        val auth = AuthSessionMutationGuard()
        val coordinator = UniversalForwardCoordinator { auth.captureAccountLease { "a" } }
        coordinator.open(listOf(source), setOf(source.id))
        val id = coordinator.activeRequestId
        auth.commitIfCurrent(auth.snapshot()) {}
        coordinator.validateSession()
        assertEquals(id, coordinator.activeRequestId)
        assertEquals(listOf(source), coordinator.request?.candidates)
    }

    @Test fun aGuestCannotStageAPrivateForwardForTheNextLogin() {
        val auth = AuthSessionMutationGuard()
        val coordinator = UniversalForwardCoordinator { auth.captureAccountLease { null } }
        coordinator.open(listOf(source), setOf(source.id))
        assertNull(coordinator.request)
    }
}
