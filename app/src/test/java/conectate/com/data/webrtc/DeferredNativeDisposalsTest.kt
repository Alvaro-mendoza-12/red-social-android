package conectate.com.data.webrtc

import org.junit.Assert.*
import org.junit.Test

class DeferredNativeDisposalsTest {
    @Test fun teardownClaimsPendingDisposalsBeforeTheirDelayedTasks() {
        val queue = DeferredNativeDisposals<Any>()
        val resource = Any()
        queue.add(resource)
        assertSame(resource, queue.drain().single())
        assertFalse(queue.claim(resource))
        assertTrue(queue.drain().isEmpty())
    }
    @Test fun completedDisposalIsNotRepeatedOnTeardown() {
        val queue = DeferredNativeDisposals<Any>()
        val resource = Any()
        queue.add(resource)
        assertTrue(queue.claim(resource))
        assertFalse(queue.claim(resource))
        assertTrue(queue.drain().isEmpty())
    }
    @Test fun nativeResourcesWithEqualValuesKeepSeparateIdentity() {
        val queue = DeferredNativeDisposals<List<Int>>()
        val first = listOf(1)
        val second = listOf(1)
        queue.add(first); queue.add(second)
        assertEquals(2, queue.drain().size)
    }
}
