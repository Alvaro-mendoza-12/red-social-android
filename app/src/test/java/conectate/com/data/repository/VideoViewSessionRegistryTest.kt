package conectate.com.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoViewSessionRegistryTest {

    @Test
    fun successfulView_isRecordedOnlyOnceForAccountAndVideo() {
        val registry = VideoViewSessionRegistry()

        assertTrue(registry.tryStart("USER-A", " Video-1 "))
        assertFalse(registry.tryStart("user-a", "video-1"))
        registry.finish("user-a", "video-1", succeeded = true)

        assertFalse(registry.tryStart("user-a", "video-1"))
        assertTrue(registry.tryStart("user-b", "video-1"))
        assertTrue(registry.tryStart("user-a", "video-2"))
    }

    @Test
    fun failedView_canBeRetried() {
        val registry = VideoViewSessionRegistry()

        assertTrue(registry.tryStart("user-a", "video-1"))
        registry.finish("user-a", "video-1", succeeded = false)

        assertTrue(registry.tryStart("user-a", "video-1"))
    }
}
