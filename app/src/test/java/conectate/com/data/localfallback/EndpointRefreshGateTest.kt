package conectate.com.data.localfallback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EndpointRefreshGateTest {
    @Test fun `concurrent failed requests share one discovery attempt then recover after cooldown`() = runTest {
        val gate = EndpointRefreshGate({ testScheduler.currentTime })
        var probes = 0
        val results = (1..40).map { async { gate.refresh { probes++; delay(1_000); false } } }.awaitAll()
        assertEquals(1, probes)
        assertTrue(results.none { it })
        testScheduler.advanceTimeBy(5_000)
        assertTrue(gate.refresh { probes++; true })
        assertEquals(2, probes)
    }

    @Test fun `a successful endpoint change is rechecked after waiting instead of restarting discovery`() = runTest {
        val gate = EndpointRefreshGate({ testScheduler.currentTime })
        var healthy = false
        var probes = 0
        (1..25).map { async { gate.refresh(skip = { healthy }) { probes++; delay(100); healthy = true; true } } }.awaitAll()
        assertEquals(1, probes)
    }

    @Test fun `cancelled attempt does not delay a valid subsequent request`() = runTest {
        val gate = EndpointRefreshGate({ testScheduler.currentTime })
        try { gate.refresh { throw CancellationException("screen left") }; fail() } catch (_: CancellationException) {}
        assertTrue(gate.refresh { true })
    }

    @Test fun `cooldown handles zero and clock reset without suppressing retries indefinitely`() = runTest {
        var now = 10L
        val gate = EndpointRefreshGate({ now })
        assertFalse(gate.refresh { false })
        assertFalse(gate.refresh { fail("retry storm"); true })
        now = 0
        assertTrue(gate.refresh { true })
    }
}
