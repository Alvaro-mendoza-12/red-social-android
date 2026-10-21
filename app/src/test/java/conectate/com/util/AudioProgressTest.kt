package conectate.com.util

import org.junit.Assert.*
import org.junit.Test

class AudioProgressTest {
    @Test fun `idle bubble shows the recorded duration instead of zero`() {
        assertEquals("0:37", audioBubbleTimeLabel(false, "0:00", audioDurationMillis("0:37")))
        assertEquals("2:05", audioBubbleTimeLabel(false, "0:00", audioDurationMillis("02:05")))
        assertEquals("62:03", audioTimeLabel(audioDurationMillis("1:02:03")))
    }
    @Test fun `active bubble distinguishes elapsed time from the complete duration`() {
        assertEquals("0:08 / 0:37", audioBubbleTimeLabel(true, "0:08", 37_000))
        assertEquals("0:00 / 0:37", audioBubbleTimeLabel(true, "0:00", 37_000))
    }
    @Test fun `unknown duration is never presented as a zero length recording`() {
        for (label in listOf(null, "", "0:00", "voice.webm", "2:60", "1:-1", "1:60:00", "999999999999999999999:01"))
            assertNull(audioDurationMillis(label))
        assertEquals("—:—", audioBubbleTimeLabel(false, "0:00", null))
        assertEquals("0:04", audioBubbleTimeLabel(true, "0:04", null))
        assertEquals("0:01", audioTimeLabel(640))
    }
    @Test fun `zero and unknown audio durations never produce NaN`() {
        for (duration in listOf(0L,-1L,Long.MIN_VALUE)) {
            assertEquals(0f,audioPlaybackProgress(0,duration))
            assertEquals(0f,audioPlaybackProgress(123,duration))
        }
    }
    @Test fun `invalid slider inputs are finite`() {
        for (value in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY))
            assertEquals(0f,finiteAudioProgress(value))
    }
    @Test fun `positions stay within the audio`() {
        assertEquals(0.5f,audioPlaybackProgress(50,100))
        assertEquals(1f,audioPlaybackProgress(150,100))
        assertEquals(0f,audioPlaybackProgress(-1,100))
        assertEquals(1f,audioPlaybackProgress(Long.MAX_VALUE,Long.MAX_VALUE))
    }
    @Test fun `technical audio file labels and URLs are hidden`() {
        for (value in listOf("recording_123.webm","voice.opus","audio.m4a?token=not-for-display","content://private/1","https://private.test/file","0:15",""))
            assertEquals("Audio",audioMessageCaption(value))
    }
    @Test fun `a meaningful audio caption is preserved`() {
        assertEquals("Explicación del ejercicio",audioMessageCaption("Explicación del ejercicio"))
    }
}
