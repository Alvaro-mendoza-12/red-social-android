package conectate.com.presentation.videos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoUploadPolicyTest {

    @Test
    fun shortCompatibleVideo_isReadyRegardlessOfNormalFileSize() {
        val decision = evaluateVideoPreparation(
            durationMs = 16_000L,
            width = 1080,
            height = 1920,
            fps = 30f,
            fileSizeBytes = 95L * 1024L * 1024L
        )

        assertFalse(decision.requiresEditor)
        assertEquals(VideoPreparationReason.READY, decision.reason)
    }

    @Test
    fun videoLongerThan45Seconds_requiresTrim() {
        val decision = evaluateVideoPreparation(
            durationMs = 45_001L,
            width = 720,
            height = 1280,
            fps = 30f,
            fileSizeBytes = 20L * 1024L * 1024L
        )

        assertTrue(decision.requiresEditor)
        assertEquals(VideoPreparationReason.TOO_LONG, decision.reason)
    }

    @Test
    fun largeButShortSource_isAcceptedBecauseDurationIsThePublishingLimit() {
        val decision = evaluateVideoPreparation(
            durationMs = 16_000L,
            width = 1080,
            height = 1920,
            fps = 30f,
            fileSizeBytes = 121L * 1024L * 1024L
        )

        assertFalse(decision.requiresEditor)
        assertEquals(VideoPreparationReason.READY, decision.reason)
    }
}
