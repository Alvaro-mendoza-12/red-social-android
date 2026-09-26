package conectate.com.presentation.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPreloadPolicyTest {
    @Test
    fun startupBufferIsProtectedFromNeighbourPreload() {
        assertFalse(
            hasProtectedVisibleVideoBuffer(
                currentMediaId = "video-1",
                selectedVideoId = "video-1",
                isReady = true,
                isPlaying = true,
                durationMs = 20_000L,
                bufferedPositionMs = 1_800L,
                currentPositionMs = 1_000L,
            )
        )
    }

    @Test
    fun preloadStartsAfterFiveSecondsAreBufferedAhead() {
        assertTrue(
            hasProtectedVisibleVideoBuffer(
                currentMediaId = "video-1",
                selectedVideoId = "video-1",
                isReady = true,
                isPlaying = true,
                durationMs = 20_000L,
                bufferedPositionMs = 7_000L,
                currentPositionMs = 2_000L,
            )
        )
    }

    @Test
    fun fullyBufferedShortVideoMayPreloadItsNeighbour() {
        assertTrue(
            hasProtectedVisibleVideoBuffer(
                currentMediaId = "video-1",
                selectedVideoId = "video-1",
                isReady = true,
                isPlaying = true,
                durationMs = 3_000L,
                bufferedPositionMs = 3_000L,
                currentPositionMs = 1_000L,
            )
        )
    }

    @Test
    fun pausedOrDifferentVideoNeverStartsPreload() {
        assertFalse(
            hasProtectedVisibleVideoBuffer(
                currentMediaId = "video-2",
                selectedVideoId = "video-1",
                isReady = true,
                isPlaying = true,
                durationMs = 20_000L,
                bufferedPositionMs = 20_000L,
                currentPositionMs = 1_000L,
            )
        )
        assertFalse(
            hasProtectedVisibleVideoBuffer(
                currentMediaId = "video-1",
                selectedVideoId = "video-1",
                isReady = true,
                isPlaying = false,
                durationMs = 20_000L,
                bufferedPositionMs = 20_000L,
                currentPositionMs = 1_000L,
            )
        )
    }
}
