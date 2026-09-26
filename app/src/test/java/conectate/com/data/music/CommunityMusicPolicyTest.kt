package conectate.com.data.music

import conectate.com.presentation.components.audioFitsFullTrackLimit
import conectate.com.presentation.components.constrainAudioRange
import conectate.com.presentation.components.defaultAudioTrimRange
import org.junit.Assert.*
import org.junit.Test

class CommunityMusicPolicyTest {
    @Test fun `four minutes stay complete and four thirty is accepted`() {
        assertTrue(CommunityMusicPolicy.acceptsDuration(240_000))
        assertTrue(CommunityMusicPolicy.acceptsDuration(270_000))
        assertFalse(CommunityMusicPolicy.acceptsDuration(270_001))
        assertFalse(CommunityMusicPolicy.acceptsDuration(4_999))
        assertEquals(0f..240_000f, defaultAudioTrimRange(240_000, 270_000, true, 270_000))
    }

    @Test fun `long songs enter crop editor without bypass through full track`() {
        assertEquals(0f..270_000f, defaultAudioTrimRange(360_000, 270_000, true, 270_000))
        assertFalse(audioFitsFullTrackLimit(360_000, 270_000))
        assertTrue(audioFitsFullTrackLimit(270_000, 270_000))
        val range = constrainAudioRange(90_000f..360_000f, 0f..270_000f, 360_000, 270_000)
        assertEquals(90_000f..360_000f, range)
    }

    @Test fun `chat full audio remains unlimited`() {
        assertEquals(0f..600_000f, defaultAudioTrimRange(600_000, 30_000, true, null))
        assertTrue(audioFitsFullTrackLimit(600_000, null))
    }
}
