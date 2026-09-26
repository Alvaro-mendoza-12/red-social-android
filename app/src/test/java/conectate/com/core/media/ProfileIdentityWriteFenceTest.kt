package conectate.com.core.media

import org.junit.Assert.*
import org.junit.Test

class ProfileIdentityWriteFenceTest {
    @Test fun `pending downloads and queued saves cannot repopulate a cleared account`() {
        val fence = ProfileIdentityWriteFence()
        val pending = fence.capture("a")
        fence.invalidate(" A ")
        assertFalse(fence.isCurrent("a", pending))
        assertTrue(fence.isCurrent("a", fence.capture("a")))
    }

    @Test fun `clearing one account does not invalidate another account's work`() {
        val fence = ProfileIdentityWriteFence()
        val pending = fence.capture("b")
        fence.invalidate("a")
        assertTrue(fence.isCurrent("b", pending))
    }
}
