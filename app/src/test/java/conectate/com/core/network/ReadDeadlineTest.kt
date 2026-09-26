package conectate.com.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadDeadlineTest {
    @Test
    fun `successful read returns its value`() = runTest {
        val result = runReadWithDeadline(1_000L) { "ready" }

        assertEquals("ready", result.getOrThrow())
    }

    @Test
    fun `slow read becomes a recoverable deadline failure`() = runTest {
        val result = runReadWithDeadline(1_000L) {
            delay(1_001L)
            "late"
        }

        assertTrue(result.exceptionOrNull() is ReadDeadlineExceededException)
    }

    @Test(expected = CancellationException::class)
    fun `caller cancellation is never converted into a network failure`() = runTest {
        runReadWithDeadline(1_000L) {
            throw CancellationException("screen replaced")
        }
    }

    @Test(expected = TimeoutCancellationException::class)
    fun `a shorter caller timeout is not converted into our deadline failure`() = runTest {
        withTimeout(100L) {
            runReadWithDeadline(1_000L) {
                delay(500L)
            }
        }
    }

    @Test
    fun `legitimate null result is not treated as a timeout`() = runTest {
        val result = runReadWithDeadline<String?>(1_000L) { null }
        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrThrow())
    }
}
