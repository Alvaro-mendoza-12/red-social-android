package conectate.com.presentation.video

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedNeighbourPreloadTest {
    @Test
    fun interruptedNeighbourResumesOnTheSameSelectionUsingOnlyItsRemainingTime() = runTest {
        val starts = mutableListOf<Pair<Long, Long>>()
        var cancelled = 0
        var completed = 0
        preloadFeedNeighbours(listOf("next"), 900L, { starting ->
            val now = testScheduler.currentTime
            if (starting) now == 0L || now >= 1_000L else now !in 100L..999L
        }, { testScheduler.currentTime }) { _, budget ->
            starts += testScheduler.currentTime to budget
            try {
                delay(300)
                completed++
            } finally {
                if (completed == 0) cancelled++
            }
        }
        assertEquals(2, starts.size)
        assertTrue(starts.last().first >= 1_000L)
        assertEquals(listOf(900L, 780L), starts.map { it.second })
        assertEquals(1, cancelled)
        assertEquals(1, completed)
    }

    @Test
    fun profileWaitsForReserveBeforeStartingTheSecondNeighbour() = runTest {
        val starts = mutableListOf<Pair<String, Long>>()
        preloadFeedNeighbours(listOf("next", "previous"), 900L, {
            testScheduler.currentTime == 0L || testScheduler.currentTime >= 500L
        }, { testScheduler.currentTime }) { url, _ ->
            starts += url to testScheduler.currentTime
            delay(30)
        }
        assertEquals(listOf("next", "previous"), starts.map { it.first })
        assertTrue(starts.last().second >= 500L)
    }

    @Test
    fun repeatedUnderrunsStopAfterOneRetry() = runTest {
        val budgets = mutableListOf<Long>()
        preloadFeedNeighbours(listOf("next"), 900L, { starting -> starting }, { testScheduler.currentTime }) { _, budget ->
            budgets += budget
            awaitCancellation()
        }
        assertEquals(listOf(900L, 780L), budgets)
    }

    @Test
    fun exhaustedBudgetCannotStartAnotherAttempt() = runTest {
        var starts = 0
        preloadFeedNeighbours(listOf("next"), 120L, { starting -> starting }, { testScheduler.currentTime }) { _, _ ->
            starts++
            awaitCancellation()
        }
        assertEquals(1, starts)
    }

    @Test
    fun changingSelectionCancelsTheWaitWithoutStartingAnotherNeighbour() = runTest {
        var hasReserve = true
        var starts = 0
        var stopped = 0
        val selection = launch {
            preloadFeedNeighbours(listOf("next", "previous"), 900L, { hasReserve }, { testScheduler.currentTime }) { _, _ ->
                starts++
                try { awaitCancellation() } finally { stopped++ }
            }
        }
        runCurrent()
        hasReserve = false
        advanceTimeBy(250L)
        selection.cancelAndJoin()
        hasReserve = true
        advanceTimeBy(1_000L)
        assertEquals(1, starts)
        assertEquals(1, stopped)
    }

    @Test
    fun completedOrFailedBestEffortReadIsNotRetried() = runTest {
        val calls = mutableListOf<String>()
        preloadFeedNeighbours(listOf("next", "previous"), 900L, { true }, { testScheduler.currentTime }) { url, _ ->
            calls += url
        }
        assertEquals(listOf("next", "previous"), calls)
    }
}
