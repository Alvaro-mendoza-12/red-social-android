package conectate.com.presentation.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MomentMusicPickerPolicyTest {
    @Test
    fun `new moment picker exposes only device and approved community music`() {
        assertEquals(
            listOf("mine" to "Mi música", "community" to "Comunidad"),
            MOMENT_MUSIC_PICKER_SECTIONS,
        )
        assertFalse(MOMENT_MUSIC_PICKER_SECTIONS.any { (id, _) ->
            id in setOf("external", "commons", "openverse", "jamendo", "trending")
        })
    }

    @Test
    fun `selected community music uses the complete audio instead of the short preview`() {
        val track = conectate.com.data.music.ApprovedMomentMusic(
            id = "community:track",
            title = "Pista",
            artist = "Autora",
            durationMs = 180_000L,
            audioUrl = "https://cdn.test/full.mp3",
            previewUrl = "https://cdn.test/preview-30s.mp3",
            sourcePageUrl = "https://cdn.test/track",
            source = "novvin_community",
            license = "ORIGINAL_OWNER",
            allowUseInMoments = true,
        )

        assertEquals("https://cdn.test/full.mp3", momentMusicRemoteUrl(track, useFullTrack = false))
        assertEquals("https://cdn.test/full.mp3", momentMusicRemoteUrl(track, useFullTrack = true))
        assertEquals("https://cdn.test/preview-30s.mp3", momentMusicRemoteUrl(track.copy(source = "other"), useFullTrack = false))
    }

}
