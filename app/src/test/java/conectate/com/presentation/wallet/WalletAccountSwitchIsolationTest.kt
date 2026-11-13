package conectate.com.presentation.wallet

import conectate.com.data.repository.SessionWriteGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletAccountSwitchIsolationTest {
    @Test
    fun `returning to original account A after A to B invalidates delayed epoch response`() {
        var currentAccount: String? = "user-a"
        val guard = SessionWriteGuard { currentAccount }

        val requestFromFirstSessionA = guard.capture()
        assertTrue(guard.isValid(requestFromFirstSessionA))

        // Switch A -> B: clears session state and bumps guard epoch.
        currentAccount = "user-b"
        guard.invalidate()
        assertFalse(guard.isValid(requestFromFirstSessionA))

        // Switch B -> A: same user ID returns, but delayed response from first epoch MUST stay rejected.
        currentAccount = "user-a"
        guard.invalidate()
        assertFalse(guard.isValid(requestFromFirstSessionA))

        val freshRequestForSecondSessionA = guard.capture()
        assertTrue(guard.isValid(freshRequestForSecondSessionA))
    }

    @Test
    fun `billing queue cancels remaining user A purchases after A to B to A switch`() {
        val queue = PlayBillingVerificationQueue()
        assertTrue(queue.enqueue("user-a", "novvin_coins_70", "token-a-1"))
        assertTrue(queue.enqueue("user-a", "novvin_coins_140", "token-a-2"))

        val first = queue.drainNext("user-a")
        assertEquals("token-a-1", first?.purchaseToken)

        // Switching away to user-b clears user-a's pending queue.
        queue.clearForOtherAccount("user-b")
        assertFalse(queue.hasPendingForAccount("user-a"))
        assertNull(queue.drainNext("user-a"))

        // Returning to user-a does not revive cancelled old purchases, only newly queried/owned ones.
        assertNull(queue.drainNext("user-a"))
        assertTrue(queue.enqueue("user-a", "novvin_coins_140", "token-a-2"))
        val reQueued = queue.drainNext("user-a")
        assertEquals("token-a-2", reQueued?.purchaseToken)
        queue.complete(reQueued!!)
    }
}
