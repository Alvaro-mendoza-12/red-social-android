package conectate.com.presentation.video

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlaybackRecoveryRunnerTest {
    @Test
    fun `swiping during discovery never applies the old video or shows its error`() = runTest {
        val response = CompletableDeferred<String>()
        var selected = "a"
        var applied = false
        var failed = false
        val runner = VideoPlaybackRecoveryRunner(this)
        runner.schedule(0, { selected == "a" }, { response.await() }, { applied = true }, { failed = true })
        runCurrent()
        selected = "b"
        response.complete("a-new.mp4")
        advanceUntilIdle()
        assertFalse(applied)
        assertFalse(failed)
    }

    @Test
    fun `a second retry replaces the first pending request`() = runTest {
        val applied = mutableListOf<String>()
        val runner = VideoPlaybackRecoveryRunner(this)
        runner.schedule(0, { true }, { delay(1_000); "old" }, { applied += it }, { fail("old failed") })
        runCurrent()
        runner.schedule(0, { true }, { "new" }, { applied += it }, { fail("new failed") })
        advanceUntilIdle()
        assertEquals(listOf("new"), applied)
    }

    @Test
    fun `leaving the screen cancels network work without exposing a failure`() = runTest {
        var cancelled = false
        val runner = VideoPlaybackRecoveryRunner(this)
        runner.schedule(0, { true }, {
            try { delay(60_000); "uri" } finally { cancelled = true }
        }, { fail("disposed playback") }, { fail("disposed error") })
        runCurrent()
        runner.cancel()
        advanceUntilIdle()
        assertTrue(cancelled)
    }

    @Test
    fun `a stalled lookup ends after its deadline with an actionable error`() = runTest {
        var failures = 0
        val runner = VideoPlaybackRecoveryRunner(this)
        runner.schedule(0, { true }, { delay(60_000); "uri" }, { fail("late playback") }, { failures++ })
        advanceUntilIdle()
        assertEquals(12_000L, testScheduler.currentTime)
        assertEquals(1, failures)
    }

    @Test
    fun `a failure that arrives after changing account cannot affect the new account`() = runTest {
        var sameAccount = true
        val runner = VideoPlaybackRecoveryRunner(this)
        runner.schedule<String>(0, { sameAccount }, {
            delay(100)
            throw IllegalStateException("old account lookup")
        }, { fail("unexpected playback") }, { fail("error leaked across accounts") })
        runCurrent()
        sameAccount = false
        advanceUntilIdle()
    }
}
