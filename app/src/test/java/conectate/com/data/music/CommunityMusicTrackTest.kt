package conectate.com.data.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunityMusicTrackTest {
    private fun track(
        state: String = "APPROVED",
        visibility: String = "PUBLIC_REUSABLE",
        allowReuse: Boolean = true,
    ) = CommunityMusicTrack(
        id = "community:11111111-1111-4111-8111-111111111111",
        title = "Pista original",
        artist = "Autora",
        audioUrl = "https://cdn.novvin.test/community-music/track.mp3",
        durationMs = 42_000L,
        license = "Derechos controlados por el autor",
        moderationState = state,
        visibility = visibility,
        allowReuse = allowReuse,
    )

    @Test
    fun `only approved reusable public tracks enter the Moment picker`() {
        assertEquals("novvin_community", track().toApprovedMomentMusic()?.source)
        assertNull(track(state = "PENDING_REVIEW").toApprovedMomentMusic())
        assertNull(track(visibility = "PUBLIC_LISTEN_ONLY").toApprovedMomentMusic())
        assertNull(track(visibility = "PRIVATE").toApprovedMomentMusic())
        assertNull(track(allowReuse = false).toApprovedMomentMusic())
    }

    @Test
    fun `community upload canonicalizes Android mime aliases and provider fallbacks`() {
        assertEquals("audio/mp4", canonicalCommunityMusicMime("audio/x-m4a", "grabacion.m4a"))
        assertEquals("audio/wav", canonicalCommunityMusicMime("audio/vnd.wave", "grabacion.wav"))
        assertEquals("audio/ogg", canonicalCommunityMusicMime("application/ogg", "grabacion.ogg"))
        assertEquals("audio/flac", canonicalCommunityMusicMime("audio/x-flac", "grabacion.flac"))
        assertEquals("audio/mpeg", canonicalCommunityMusicMime(null, "mi canción.MP3"))
        assertNull(canonicalCommunityMusicMime("application/octet-stream", "archivo.bin"))
        assertEquals("image/jpeg", canonicalCommunityCoverMime("image/jpg", "portada.jpg"))
        assertEquals("image/webp", canonicalCommunityCoverMime(null, "portada.WEBP"))
        assertNull(canonicalCommunityCoverMime("application/octet-stream", "portada.gif"))
    }
}
