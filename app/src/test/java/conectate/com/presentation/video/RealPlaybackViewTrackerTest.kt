package conectate.com.presentation.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealPlaybackViewTrackerTest {

    @Test
    fun sample_requiresActualPlaybackAcrossTheThreshold() {
        val tracker = RealPlaybackViewTracker(
            thresholdMs = 1_000L,
            maxContinuousSampleGapMs = 400L
        )

        assertFalse(tracker.sample("video-a", isActuallyPlaying = true, elapsedRealtimeMs = 0L))
        assertFalse(tracker.sample("video-a", isActuallyPlaying = true, elapsedRealtimeMs = 250L))
        assertFalse(tracker.sample("video-a", isActuallyPlaying = false, elapsedRealtimeMs = 500L))
        assertFalse(tracker.sample("video-a", isActuallyPlaying = true, elapsedRealtimeMs = 750L))
        assertFalse(tracker.sample("video-a", isActuallyPlaying = true, elapsedRealtimeMs = 1_000L))
        assertFalse(tracker.sample("video-a", isActuallyPlaying = true, elapsedRealtimeMs = 1_250L))
        assertTrue(tracker.sample("video-a", isActuallyPlaying = true, elapsedRealtimeMs = 1_500L))

        assertEquals(1_000L, tracker.playedMs("video-a"))
    }

    @Test
    fun sample_emitsOnlyOnceDespiteFurtherSamplesAndListRefreshes() {
        val tracker = RealPlaybackViewTracker(thresholdMs = 500L, maxContinuousSampleGapMs = 300L)

        assertFalse(tracker.sample(" Video-A ", true, 0L))
        assertFalse(tracker.sample("video-a", true, 250L))
        assertTrue(tracker.sample("VIDEO-A", true, 500L))
        assertFalse(tracker.sample("video-a", true, 750L))
        assertFalse(tracker.sample("video-a", true, 1_000L))
    }

    @Test
    fun sample_doesNotChargeTimeToAHiddenVideoOrAcrossLongLifecycleGap() {
        val tracker = RealPlaybackViewTracker(thresholdMs = 500L, maxContinuousSampleGapMs = 300L)

        assertFalse(tracker.sample("video-a", true, 0L))
        assertFalse(tracker.sample("video-a", true, 250L))
        assertFalse(tracker.sample("video-b", true, 300L))
        assertFalse(tracker.sample("video-b", true, 550L))
        assertFalse(tracker.sample("video-a", true, 600L))
        assertTrue(tracker.sample("video-a", true, 850L))

        val lifecycleTracker = RealPlaybackViewTracker(
            thresholdMs = 500L,
            maxContinuousSampleGapMs = 300L
        )
        assertFalse(lifecycleTracker.sample("video-c", true, 0L))
        assertFalse(lifecycleTracker.sample("video-c", true, 5_000L))
        assertFalse(lifecycleTracker.sample("video-c", true, 5_250L))
        assertTrue(lifecycleTracker.sample("video-c", true, 5_500L))
    }

    @Test
    fun allowRetry_reopensAnAlreadyReachedThreshold() {
        val tracker = RealPlaybackViewTracker(thresholdMs = 250L, maxContinuousSampleGapMs = 300L)

        assertFalse(tracker.sample("video-a", true, 0L))
        assertTrue(tracker.sample("video-a", true, 250L))
        tracker.allowRetry("video-a")

        assertTrue(tracker.sample("video-a", true, 500L))
    }
}
