package conectate.com.presentation.video

import conectate.com.data.model.Video
import org.junit.Assert.*
import org.junit.Test

class VideoEntryPlaybackTest {
    @Test fun normalVideoEntryRestoresPositionWithinDuration() {
        val entry = resolveVideoEntryPlayback(Video(id = "v"), 90_000L, 0L, 60_000L)
        assertEquals(59_999L, entry.positionMs)
        assertNull(entry.loop)
        assertFalse(entry.rejectedClip)
    }
    @Test fun validClipRetainsItsLoopInTheVerticalPlayer() {
        val entry = resolveVideoEntryPlayback(Video(id = "v"), 10_000L, 20_000L, 60_000L)
        assertEquals(10_000L, entry.positionMs)
        assertEquals(VideoReviewLoop("v", 10_000L, 20_000L), entry.loop)
    }
    @Test fun revokedClipStartsAtBeginningWithoutLoop() {
        val entry = resolveVideoEntryPlayback(Video(id = "v", allowClipSharing = false), 10_000L, 20_000L, 60_000L)
        assertEquals(0L, entry.positionMs)
        assertNull(entry.loop)
        assertTrue(entry.rejectedClip)
    }
    @Test fun outOfBoundsClipIsRejected() {
        assertTrue(resolveVideoEntryPlayback(Video(id = "v"), 55_000L, 65_000L, 60_000L).rejectedClip)
    }
}
