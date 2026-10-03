package conectate.com.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ObservedRefreshTest {
    @Test fun noDiscoveryRequestsWithoutObservers() = runTest {
        val count = MutableStateFlow(0); var calls = 0
        backgroundScope.launch { refreshWhileObserved(count, 100) { calls++ } }
        runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(0, calls)
    }
    @Test fun entryRefreshesImmediatelyAndExitStopsPolling() = runTest {
        val count = MutableStateFlow(0); var calls = 0
        backgroundScope.launch { refreshWhileObserved(count, 100) { calls++ } }
        runCurrent(); count.value = 1; runCurrent(); assertEquals(1, calls)
        advanceTimeBy(100); runCurrent(); assertEquals(2, calls)
        count.value = 0; runCurrent(); advanceTimeBy(1000); runCurrent()
        assertEquals(2, calls)
        count.value = 1; runCurrent(); assertEquals(3, calls)
    }
    @Test fun exitCancelsAPendingRead() = runTest {
        val count = MutableStateFlow(1); val pending = CompletableDeferred<Unit>()
        var cancelled = false
        backgroundScope.launch { refreshWhileObserved(count, 100) {
            try { pending.await() } finally { cancelled = true }
        } }
        runCurrent(); count.value = 0; runCurrent()
        assertTrue(cancelled)
    }
    @Test fun aFailedReadDoesNotDisableFutureRefreshes() = runTest {
        val count = MutableStateFlow(1); var calls = 0
        backgroundScope.launch { refreshWhileObserved(count, 100) {
            calls++; if (calls == 1) throw IllegalStateException()
        } }
        runCurrent(); advanceTimeBy(100); runCurrent(); assertEquals(2, calls)
    }
}
