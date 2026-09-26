package conectate.com.presentation.video

import conectate.com.presentation.components.shouldAnimateProfileAmbient
import org.junit.Assert.*
import org.junit.Test

class LightweightRenderingPolicyTest {
    @Test fun `prefetch is suppressed when saving data backgrounded offline or reading an overlay`() {
        assertTrue(shouldPrefetchFeedVideo(true, false, true, false))
        assertFalse(shouldPrefetchFeedVideo(true, true, true, false))
        assertFalse(shouldPrefetchFeedVideo(false, false, true, false))
        assertFalse(shouldPrefetchFeedVideo(true, false, false, false))
        assertFalse(shouldPrefetchFeedVideo(true, false, true, true))
    }

    @Test fun `cellular playback needs an explicit tap when automatic playback is disabled`() {
        assertFalse(shouldPlayFeedVideo(true, "a", true, false, null))
        assertFalse(shouldPlayFeedVideo(true, "b", true, false, "a"))
        assertTrue(shouldPlayFeedVideo(true, "a", true, false, "a"))
        assertTrue(shouldPlayFeedVideo(true, "a", false, false, null))
        assertTrue(shouldPlayFeedVideo(true, "a", true, true, null))
        assertFalse(shouldPlayFeedVideo(false, "a", true, false, "a"))
        assertFalse(shouldPlayFeedVideo(true, null, false, true, null))
    }

    @Test fun `static and unknown profile effects do not schedule frames`() {
        assertFalse(shouldAnimateProfileAmbient("none", false, true))
        assertFalse(shouldAnimateProfileAmbient("unknown", false, true))
        assertTrue(shouldAnimateProfileAmbient(" WAVES ", false, true))
        assertFalse(shouldAnimateProfileAmbient("waves", true, true))
        assertFalse(shouldAnimateProfileAmbient("waves", false, false))
    }
}
