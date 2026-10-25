package conectate.com.data.repository.message

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatMediaPrefetchGateTest {
    private fun key(source: String, account: String = "account", epoch: Long = 0L) =
        ChatMediaPrefetchGate.Key(account, epoch, source)

    @Test fun overlappingSnapshotsDownloadAnObjectOnceAndCanRetryAfterCompletion() = runTest {
        val gate = ChatMediaPrefetchGate(backgroundScope)
        val release = CompletableDeferred<Unit>()
        var downloads = 0
        assertTrue(gate.enqueue(key("image"), { true }) { downloads++; release.await() })
        repeat(100) { assertFalse(gate.enqueue(key("image"), { true }) { downloads++ }) }
        runCurrent()
        assertEquals(1, downloads)
        release.complete(Unit)
        runCurrent()
        assertTrue(gate.enqueue(key("image"), { true }) { downloads++ })
        runCurrent()
        assertEquals(2, downloads)
    }

    @Test fun queueIsBoundedAndOnlyTwoTransfersRunAtOnce() = runTest {
        val gate = ChatMediaPrefetchGate(backgroundScope, parallelism = 2, capacity = 4)
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        repeat(4) { index ->
            assertTrue(gate.enqueue(key("image-$index"), { true }) {
                active++
                maximum = maxOf(maximum, active)
                release.await()
                active--
            })
        }
        assertFalse(gate.enqueue(key("overflow"), { true }) { fail("Queue exceeded its limit") })
        runCurrent()
        assertEquals(2, active)
        release.complete(Unit)
        runCurrent()
        assertEquals(0, active)
        assertEquals(2, maximum)
    }

    @Test fun pendingDownloadsRecheckAccessAndRemainScopedToTheAccountAndSession() = runTest {
        val gate = ChatMediaPrefetchGate(backgroundScope, parallelism = 1)
        val release = CompletableDeferred<Unit>()
        var allowed = true
        val delivered = mutableListOf<String>()
        gate.enqueue(key("busy"), { true }) { release.await() }
        gate.enqueue(key("image"), { allowed }) { delivered += "old" }
        gate.enqueue(key("image", epoch = 1), { true }) { delivered += "new-session" }
        gate.enqueue(key("image", account = "other"), { true }) { delivered += "other-account" }
        runCurrent()
        allowed = false
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("new-session", "other-account"), delivered)
        assertTrue(gate.enqueue(key("image"), { true }) { delivered += "retry" })
        runCurrent()
        assertEquals("retry", delivered.last())
    }
}
