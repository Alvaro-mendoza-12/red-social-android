package conectate.com.presentation.social

import org.junit.Assert.assertEquals
import org.junit.Test

class MomentMusicDurationTest {
    @Test
    fun `duration shown to the user is rounded and formatted compactly`() {
        assertEquals("0:06", formatMomentMusicDuration(6_000L))
        assertEquals("1:00", formatMomentMusicDuration(59_600L))
        assertEquals("0:00", formatMomentMusicDuration(-1L))
    }
}
