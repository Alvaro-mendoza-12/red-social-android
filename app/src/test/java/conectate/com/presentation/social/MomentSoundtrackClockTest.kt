package conectate.com.presentation.social

import org.junit.Assert.*
import org.junit.Test

class MomentSoundtrackClockTest {
    @Test fun `ended buffering or not yet started video holds the soundtrack`() {
        assertFalse(momentSoundtrackClockRunning(true, isVideo = true, videoPlaying = false))
        assertTrue(momentSoundtrackClockRunning(true, isVideo = true, videoPlaying = true))
    }

    @Test fun `images do not need a video player to run their soundtrack`() {
        assertTrue(momentSoundtrackClockRunning(true, isVideo = false, videoPlaying = false))
        assertFalse(momentSoundtrackClockRunning(false, isVideo = false, videoPlaying = false))
    }

    @Test fun `paused clocks stay paused even if player reports playing`() {
        assertFalse(momentSoundtrackClockRunning(false, isVideo = true, videoPlaying = true))
    }
}
