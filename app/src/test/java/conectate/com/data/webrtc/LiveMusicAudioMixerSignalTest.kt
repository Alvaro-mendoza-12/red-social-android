package conectate.com.data.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMusicAudioMixerSignalTest {
    @Test
    fun rejectsSilentOrEffectivelySilentPcm() {
        assertFalse(hasAudiblePcmSignal(ShortArray(2_048)))
        assertFalse(hasAudiblePcmSignal(ShortArray(2_048) { 1 }))
    }

    @Test
    fun acceptsAnAudiblePcmSignal() {
        val samples = ShortArray(4_096) { index -> if (index % 2 == 0) 1_200 else -1_200 }
        assertTrue(hasAudiblePcmSignal(samples))
    }
}
