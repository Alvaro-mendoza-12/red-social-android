package conectate.com.presentation.social

import org.junit.Assert.assertEquals
import org.junit.Test

class MomentLocalMusicDurationTest {
    @Test fun sixSecondPhotoDoesNotTruncateThreeMinuteSource() {
        assertEquals(180_000L to 6_000L, localMomentMusicDurations(180_000L, 6_000L))
        val policy = MomentMusicTrimPolicy(180_000L, 6_000L)
        assertEquals(120_000f..126_000f, policy.moveStart(120_000f, 0f..6_000f))
    }
    @Test fun sourceLengthAndMomentLengthAreSeparate() {
        assertEquals(300_000L to 60_000L, localMomentMusicDurations(300_000L, 200_000L))
        assertEquals(3_000L to 3_000L, localMomentMusicDurations(3_000L, 6_000L))
    }
}
