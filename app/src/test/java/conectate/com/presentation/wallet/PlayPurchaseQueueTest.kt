package conectate.com.presentation.wallet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayPurchaseQueueTest {
    @Test fun verifiesBothPurchasesAndCoalescesOnlyPendingDuplicates() = runTest {
        val busy = mutableListOf<Boolean>()
        val seen = mutableListOf<String>()
        val queue = PlayPurchaseQueue(this) { busy.add(it) }
        val release = CompletableDeferred<Unit>()
        queue.submit("alice", "one") { seen.add("one"); release.await() }
        queue.submit("alice", "two") { seen.add("two") }
        queue.submit("alice", "one") { seen.add("duplicate") }
        yield()
        assertEquals(listOf("one"), seen)
        release.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("one", "two"), seen)
        assertEquals(listOf(true, false), busy)
        queue.submit("alice", "one") { seen.add("retry") }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("one", "two", "retry"), seen)
    }
}
