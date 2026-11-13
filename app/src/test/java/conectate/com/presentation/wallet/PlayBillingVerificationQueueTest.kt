package conectate.com.presentation.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayBillingVerificationQueueTest {
    @Test
    fun `queues and drains multiple purchases in FIFO order without dropping batch items`() {
        val queue = PlayBillingVerificationQueue()

        assertTrue(queue.enqueue("user-a", "novvin_coins_70", "token-1"))
        assertTrue(queue.enqueue("user-a", "novvin_coins_350", "token-2"))
        assertFalse(queue.enqueue("user-a", "novvin_coins_350", "token-2"))

        val first = queue.drainNext("user-a")
        assertEquals("token-1", first?.purchaseToken)
        // Busy while first purchase is verifying: a second drain does not overlap.
        assertNull(queue.drainNext("user-a"))

        // Duplicate notification while in-flight is ignored.
        assertFalse(queue.enqueue("user-a", "novvin_coins_70", "token-1"))

        queue.complete(first!!)
        val second = queue.drainNext("user-a")
        assertEquals("token-2", second?.purchaseToken)
        queue.complete(second!!)
        assertNull(queue.drainNext("user-a"))
    }

    @Test
    fun `drops purchases queued for another account during account switch`() {
        val queue = PlayBillingVerificationQueue()

        assertTrue(queue.enqueue("user-a", "novvin_coins_70", "token-a"))
        assertTrue(queue.enqueue("user-b", "novvin_coins_140", "token-b"))

        queue.clearForOtherAccount("user-b")
        val next = queue.drainNext("user-b")
        assertEquals("user-b", next?.accountId)
        assertEquals("token-b", next?.purchaseToken)
        queue.complete(next!!)
        assertFalse(queue.hasPendingForAccount("user-a"))
        assertNull(queue.drainNext("user-b"))
    }
}
