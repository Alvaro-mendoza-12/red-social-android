package conectate.com.data.music

import org.junit.Assert.assertEquals
import org.junit.Test

class MomentMusicCompositionExporterTest {
    @Test
    fun `plan clamps gains segment and fades before transformer`() {
        val plan = normalizedMomentMusicMixPlan(
            musicStartMs = -200,
            musicEndMs = 2_000,
            musicVolume = 4f,
            originalAudioVolume = 0.8f,
            fadeInMs = 9_000,
            fadeOutMs = 9_000,
            duckOriginalAudio = true,
        )

        assertEquals(0L, plan.musicStartMs)
        assertEquals(2_000L, plan.musicEndMs)
        assertEquals(1f, plan.musicVolume)
        assertEquals(0.28f, plan.originalAudioVolume, 0.0001f)
        assertEquals(1_000L, plan.fadeInMs)
        assertEquals(1_000L, plan.fadeOutMs)
    }

    @Test
    fun `plan preserves independent original and music volumes without ducking`() {
        val plan = normalizedMomentMusicMixPlan(
            musicStartMs = 5_000,
            musicEndMs = 25_000,
            musicVolume = 0.6f,
            originalAudioVolume = 0.7f,
            fadeInMs = 500,
            fadeOutMs = 750,
            duckOriginalAudio = false,
        )

        assertEquals(0.6f, plan.musicVolume)
        assertEquals(0.7f, plan.originalAudioVolume)
        assertEquals(20_000L, plan.musicDurationMs)
    }
}
