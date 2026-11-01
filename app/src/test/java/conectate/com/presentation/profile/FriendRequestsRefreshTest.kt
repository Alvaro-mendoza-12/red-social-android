package conectate.com.presentation.profile

import conectate.com.data.model.User
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FriendRequestsRefreshTest {
    private val known = User(id = "confirmed-person")
    private val incoming = User(id = "new-request")
    private val confirmedAt = 1_700_000_000_000L

    @Test fun recentReadFailurePreservesKnownConfirmationsAndFreshPendingRequests() {
        val result = mergeFriendRequests(listOf(incoming), Result.failure(IllegalStateException("offline")),
            listOf(known), mapOf(known.id to confirmedAt), emptySet())
        assertEquals(listOf(incoming, known), result.users)
        assertEquals(mapOf(known.id to confirmedAt), result.confirmations)
        assertTrue(result.recentFailed)
    }

    @Test fun failureWithoutKnownPeopleIsAnErrorRatherThanAnAuthoritativeEmptyList() {
        val result = mergeFriendRequests(emptyList(), Result.failure(IllegalStateException("offline")),
            emptyList(), emptyMap(), emptySet())
        assertTrue(result.users.isEmpty())
        assertTrue(result.recentFailed)
    }

    @Test fun successfulEmptyRecentReadRemovesOldConfirmationsAndClearsTheError() {
        val result = mergeFriendRequests(listOf(incoming), Result.success(emptyList()),
            listOf(known), mapOf(known.id to confirmedAt), emptySet())
        assertEquals(listOf(incoming), result.users)
        assertTrue(result.confirmations.isEmpty())
        assertFalse(result.recentFailed)
    }

    @Test fun pendingRowsWinOverOldConfirmationAndDismissalsApplyDuringFailure() {
        val same = User(id = known.id.uppercase())
        val result = mergeFriendRequests(listOf(same), Result.failure(IllegalStateException("offline")),
            listOf(known, incoming), mapOf(known.id to confirmedAt, incoming.id to confirmedAt), setOf(incoming.id.uppercase()))
        assertEquals(listOf(same), result.users)
        assertTrue(result.confirmations.isEmpty())
    }

    @Test fun pendingRefreshKeepsKnownConfirmationWithoutReportingAnErrorPrematurely() {
        val result = mergeFriendRequests(listOf(incoming), null, listOf(known),
            mapOf(known.id to confirmedAt), emptySet())
        assertEquals(listOf(incoming, known), result.users)
        assertFalse(result.recentFailed)
    }

    @Test fun cancellationDoesNotBecomeAVisibleNetworkError() {
        try {
            mergeFriendRequests(emptyList(), Result.failure(CancellationException("replaced")),
                emptyList(), emptyMap(), emptySet())
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
