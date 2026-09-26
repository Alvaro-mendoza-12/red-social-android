package conectate.com.data.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentMusicPolicyTest {
    @Test
    fun `only public domain and CC0 licenses are approved`() {
        assertEquals("CC0 1.0", approvedMomentMusicLicenseOrNull("CC0"))
        assertEquals(
            "CC0 1.0",
            approvedMomentMusicLicenseOrNull("Creative Commons Zero, Public Domain Dedication"),
        )
        assertEquals("Public Domain", approvedMomentMusicLicenseOrNull("Public domain"))
        assertEquals(null, approvedMomentMusicLicenseOrNull("CC BY 4.0"))
        assertEquals(null, approvedMomentMusicLicenseOrNull("CC BY-SA 3.0"))
    }

    @Test
    fun `only Media3 compatible audio containers are exposed`() {
        assertTrue(isPlayableMomentMusicMime("audio/mpeg"))
        assertTrue(isPlayableMomentMusicMime("application/ogg"))
        assertTrue(isPlayableMomentMusicMime("audio/flac"))
        assertFalse(isPlayableMomentMusicMime("audio/midi"))
        assertFalse(isPlayableMomentMusicMime("text/html"))
    }

    @Test
    fun `external catalog requires approved source urls duration and license`() {
        val valid = ApprovedMomentMusic(
            id = "openverse:0b2b8bcb-35f4-4a8e-852b-92a50f7c91e8",
            title = "Piano",
            artist = "Autora",
            durationMs = 30_000L,
            audioUrl = "https://cdn.example.test/piano.mp3",
            sourcePageUrl = "https://openverse.org/audio/track",
            source = "openverse",
            provider = "openverse",
            license = "CC0 1.0",
            allowUseInMoments = true,
        )
        assertTrue(isApprovedMomentMusicTrack(valid))
        assertFalse(isApprovedMomentMusicTrack(valid.copy(license = "CC BY 4.0")))
        assertFalse(isApprovedMomentMusicTrack(valid.copy(audioUrl = "http://unsafe.test/audio.mp3")))
        assertFalse(isApprovedMomentMusicTrack(valid.copy(durationMs = 4_999L)))
        assertFalse(isApprovedMomentMusicTrack(valid.copy(allowUseInMoments = false)))
    }
}
