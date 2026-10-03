package conectate.com.util

import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveAudioBufferTest {
    @Test fun `short audio starts early and retains a larger reservoir`() {
        assertTrue(InteractiveAudioBuffer.START_MS in 100..500)
        assertTrue(InteractiveAudioBuffer.REBUFFER_MS >= InteractiveAudioBuffer.START_MS)
        assertTrue(InteractiveAudioBuffer.MIN_MS >= InteractiveAudioBuffer.REBUFFER_MS)
        assertTrue(InteractiveAudioBuffer.MAX_MS >= InteractiveAudioBuffer.MIN_MS)
    }
}
