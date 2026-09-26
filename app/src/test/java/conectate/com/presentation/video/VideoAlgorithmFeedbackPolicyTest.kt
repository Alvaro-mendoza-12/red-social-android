package conectate.com.presentation.video

import conectate.com.data.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAlgorithmFeedbackPolicyTest {
    @Test
    fun `local feedback block keeps rejected content out of refreshed pages`() {
        val video = Video(id = "v1", userId = "creator-1", tags = listOf("Futbol"), duration = 30)
        assertTrue(VideoFeedbackBlock("content", "v1").matches(video))
        assertTrue(VideoFeedbackBlock("creator", "creator-1").matches(video))
        assertTrue(VideoFeedbackBlock("topic", "futbol").matches(video))
        assertTrue(VideoFeedbackBlock("format", "short-video").matches(video))
        assertFalse(VideoFeedbackBlock("topic", "cocina").matches(video))
        assertFalse(VideoFeedbackBlock("format", "long-video").matches(video))
    }

    @Test
    fun `format feedback never uses the global video bucket`() {
        assertEquals("short-video", VideoAlgorithmFeedbackPolicy.formatKey(30))
        assertEquals("long-video", VideoAlgorithmFeedbackPolicy.formatKey(61))
        assertEquals("long-video", VideoAlgorithmFeedbackPolicy.formatKey(0))
    }

    @Test
    fun `generic topics cannot become a global negative rule`() {
        assertNull(VideoAlgorithmFeedbackPolicy.topicKey("video-general"))
        assertNull(VideoAlgorithmFeedbackPolicy.topicKey("general"))
        assertNull(VideoAlgorithmFeedbackPolicy.topicKey("  "))
        assertEquals("futbol", VideoAlgorithmFeedbackPolicy.topicKey("  Futbol "))
    }

    @Test
    fun `empty authoritative first page falls back instead of blanking feed`() {
        assertFalse(VideoAlgorithmFeedbackPolicy.shouldUseAuthoritativeInitialPage(0))
        assertTrue(VideoAlgorithmFeedbackPolicy.shouldUseAuthoritativeInitialPage(1))
        assertTrue(VideoAlgorithmFeedbackPolicy.shouldUseAuthoritativeInitialPage(24))
    }
}
