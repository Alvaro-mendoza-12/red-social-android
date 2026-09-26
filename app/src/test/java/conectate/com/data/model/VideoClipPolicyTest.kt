package conectate.com.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoClipPolicyTest {
    @Test fun `clip range stays inside source and lasts five to sixty seconds`() {
        assertEquals(VideoClipRange(12_000, 20_000), validVideoClipRange(12_000, 20_000, 45_000))
        assertNull(validVideoClipRange(12_000, 16_999, 45_000))
        assertNull(validVideoClipRange(12_000, 45_001, 45_000))
        assertNull(validVideoClipRange(-1, 10_000, 45_000))
        assertNull(validVideoClipRange(0, 61_000, 120_000))
    }
}
