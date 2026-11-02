package conectate.com.presentation.profile

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationRefreshTest {
    @Test fun `continuous events do not cancel or postpone the active read and get a final refresh`() = runTest {
        val first = CompletableDeferred<Unit>()
        var calls = 0
        val refresh = NotificationRefresh(backgroundScope, {
            calls++
            if (calls == 1) first.await()
        }, { fail(it.message) }, windowMs = 100)
        repeat(10) { refresh.request() }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(1, calls)
        repeat(20) { refresh.request(); advanceTimeBy(20); runCurrent() }
        assertEquals(1, calls)
        first.complete(Unit)
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, calls)
    }

    @Test fun `old cancelled pass cannot finish or replace the new refresh reservation`() = runTest {
        val old = CompletableDeferred<Unit>()
        val next = CompletableDeferred<Unit>()
        var calls = 0
        val refresh = NotificationRefresh(backgroundScope, {
            when (++calls) {
                1 -> withContext(NonCancellable) { old.await() }
                2 -> next.await()
                else -> Unit
            }
        }, { fail(it.message) }, windowMs = 100)
        refresh.request(); runCurrent(); advanceTimeBy(101); runCurrent()
        refresh.cancel()
        refresh.request(); runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(2, calls)
        old.complete(Unit); runCurrent()
        refresh.request(); advanceTimeBy(200); runCurrent()
        assertEquals(2, calls)
        next.complete(Unit); runCurrent(); advanceTimeBy(101); runCurrent()
        assertEquals(3, calls)
    }
}
