package conectate.com.data.supabase

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamUploadTimingCalculatorTest {

    @Test
    fun `separates request body transfer from server response wait`() {
        val result = StreamUploadTimingCalculator.calculate(
            uploadStartedElapsedMs = 100L,
            requestBodyStartedElapsedMs = 120L,
            requestBodyFinishedElapsedMs = 1_120L,
            completedElapsedMs = 1_520L,
            sizeBytes = 1_024_000L,
        )

        assertEquals(1_000L, result.transportMs)
        assertEquals(400L, result.responseWaitMs)
        assertEquals(1_000L, result.throughputKibPerSecond)
    }

    @Test
    fun `request that fails before body write does not invent transfer timing`() {
        val result = StreamUploadTimingCalculator.calculate(
            uploadStartedElapsedMs = 100L,
            requestBodyStartedElapsedMs = 0L,
            requestBodyFinishedElapsedMs = 0L,
            completedElapsedMs = 500L,
            sizeBytes = 1_024L,
        )

        assertEquals(0L, result.transportMs)
        assertEquals(0L, result.responseWaitMs)
        assertEquals(0L, result.throughputKibPerSecond)
    }

    @Test
    fun `clock anomalies remain non negative and bounded`() {
        val result = StreamUploadTimingCalculator.calculate(
            uploadStartedElapsedMs = 1_000L,
            requestBodyStartedElapsedMs = 900L,
            requestBodyFinishedElapsedMs = 800L,
            completedElapsedMs = 950L,
            sizeBytes = 4_096L,
        )

        assertEquals(0L, result.transportMs)
        assertEquals(0L, result.responseWaitMs)
        assertEquals(0L, result.throughputKibPerSecond)
    }
}
