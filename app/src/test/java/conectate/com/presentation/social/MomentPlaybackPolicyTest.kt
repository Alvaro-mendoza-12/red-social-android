package conectate.com.presentation.social

import org.junit.Assert.assertEquals
import org.junit.Test

class MomentPlaybackPolicyTest {
    @Test
    fun `image moment never lasts less than six seconds`() {
        assertEquals(6_000L, resolvedImageMomentPlaybackDurationMs(null, null))
        assertEquals(6_000L, resolvedImageMomentPlaybackDurationMs(2_500L, 3_000L))
    }

    @Test
    fun `selected music cannot extend the fixed image window`() {
        assertEquals(6_000L, resolvedImageMomentPlaybackDurationMs(6_000L, 18_000L))
    }

    @Test
    fun `image moment ignores inconsistent device metadata`() {
        assertEquals(6_000L, resolvedImageMomentPlaybackDurationMs(90_000L, 80_000L))
    }

    @Test
    fun `monotonic clock reaches completion only after six real seconds`() {
        assertEquals(0f, imageMomentProgressAt(0f, 10_000L, 10_000L, 6_000L), 0.0001f)
        assertEquals(0.5f, imageMomentProgressAt(0f, 10_000L, 13_000L, 6_000L), 0.0001f)
        assertEquals(0.999f, imageMomentProgressAt(0f, 10_000L, 15_994L, 6_000L), 0.0001f)
        assertEquals(1f, imageMomentProgressAt(0f, 10_000L, 16_000L, 6_000L), 0.0001f)
    }

    @Test
    fun `pause resumes from retained progress without shortening interval`() {
        assertEquals(4_500L, imageMomentRemainingDurationMs(6_000L, 0.25f))
        assertEquals(0.5f, imageMomentProgressAt(0.25f, 20_000L, 21_500L, 6_000L), 0.0001f)
        assertEquals(4_500L, imageMomentRemainingDurationMs(6_000L, 0.25f))
    }
}
