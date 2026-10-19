package conectate.com.presentation.videos

import org.junit.Assert.*
import org.junit.Test

class VideoColorResetTest {
    @Test fun resetPreservesTimingAndAudioButRemovesAllColorChanges() {
        val clip = EditorClip(id = 5, startMs = 1000, durationMs = 5000, startOffsetMs = 2000,
            brightness = .6f, contrast = 1.8f, saturation = .4f, filterType = FilterType.SEPIA,
            customLutId = "custom", customLutTitle = "Color", originalAudioVolume = .3f, speed = 1.5f)
        val reset = clip.withOriginalColor()
        assertEquals(clip.startMs, reset.startMs); assertEquals(clip.durationMs, reset.durationMs)
        assertEquals(clip.startOffsetMs, reset.startOffsetMs); assertEquals(clip.speed, reset.speed, 0f)
        assertEquals(clip.originalAudioVolume, reset.originalAudioVolume, 0f)
        assertEquals(0f, reset.brightness, 0f); assertEquals(1f, reset.contrast, 0f); assertEquals(1f, reset.saturation, 0f)
        assertEquals(FilterType.NONE, reset.filterType); assertNull(reset.customLutId); assertNull(reset.customLutTitle)
    }
}
