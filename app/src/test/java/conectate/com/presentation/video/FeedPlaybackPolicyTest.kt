package conectate.com.presentation.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedPlaybackPolicyTest {
    @Test fun reviewLoopClampsNearTheEndAndRejectsUnknownDurations() {
        assertEquals(VideoReviewLoop("v", 8_000, 18_000), VideoReviewLoop.at("v", 8_000, 45_000))
        assertEquals(VideoReviewLoop("v", 44_000, 45_000), VideoReviewLoop.at("v", 50_000, 45_000))
        assertEquals(VideoReviewLoop("v", 0, 2_000), VideoReviewLoop.at("v", -200, 2_000))
        assertNull(VideoReviewLoop.at("v", 0, -1))
        assertNull(VideoReviewLoop.at("v", 0, 999))
        assertNull(VideoReviewLoop.at("", 0, 45_000))
    }

    @Test fun watchBudgetExcludesPauseBufferingAndAppBackgroundGaps() {
        val budget = VideoWatchBudget()
        assertEquals(0, budget.sample(0, true))
        assertEquals(250, budget.sample(250, true))
        assertEquals(250, budget.sample(500, false))
        assertEquals(250, budget.sample(1_000, false))
        assertEquals(250, budget.sample(1_250, true))
        assertEquals(500, budget.sample(1_500, true))
        assertEquals(500, budget.sample(60_000, true))
        assertEquals(750, budget.sample(60_250, true))
    }

    @Test fun aNewBlockClearsBothBudgetAndTheOldClockSample() {
        val budget = VideoWatchBudget()
        budget.sample(0, true)
        budget.sample(250, true)
        budget.reset()
        assertEquals(0, budget.sample(500, true))
        assertEquals(250, budget.sample(750, true))
        assertEquals(250, budget.sample(10, true))
    }

    @Test fun markerTimeLabelsRemainReadableAcrossMinutes() {
        assertEquals("0:00", videoTimeLabel(-10))
        assertEquals("0:09", videoTimeLabel(9_800))
        assertEquals("2:05", videoTimeLabel(125_000))
    }
}
