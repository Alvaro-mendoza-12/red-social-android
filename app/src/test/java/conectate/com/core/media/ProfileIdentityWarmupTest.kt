package conectate.com.core.media

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileIdentityWarmupTest {
    @Test fun `warmup visits every identity with at most three concurrent loads`() = runTest {
        var active = 0
        var peak = 0
        val visited = mutableSetOf<Int>()
        warmProfileIdentityBatch((1..140).toList()) {
            active++
            peak = maxOf(active, peak)
            delay(10)
            visited += it
            active--
        }
        assertEquals(3, peak)
        assertEquals((1..140).toSet(), visited)
    }

    @Test fun `caller cancellation stops queued background work`() = runTest {
        var started = 0
        val job = launch {
            warmProfileIdentityBatch((1..100).toList()) {
                started++
                delay(1_000)
            }
        }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertEquals(3, started)
    }
}
