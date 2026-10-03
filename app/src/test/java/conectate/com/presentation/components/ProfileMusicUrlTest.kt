package conectate.com.presentation.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMusicUrlTest {
    @Test
    fun `direct HTTPS and local audio sources are supported`() {
        assertTrue(isSupportedDirectAudioSource("https://media.example.com/song.mp3"))
        assertTrue(isSupportedDirectAudioSource("https://media.example.com/song.m4a?token=abc"))
        assertTrue(isSupportedDirectAudioSource("content://media/external/audio/123"))
        assertTrue(isSupportedDirectAudioSource("https://media.example.com/storage/v1/object/public/audios/account/audio-without-extension"))
    }

    @Test
    fun `web pages and insecure network sources are rejected`() {
        assertFalse(isSupportedDirectAudioSource("https://www.youtube.com/watch?v=example"))
        assertFalse(isSupportedDirectAudioSource("https://example.com/music-page"))
        assertFalse(isSupportedDirectAudioSource("http://media.example.com/song.mp3"))
        assertFalse(isSupportedDirectAudioSource("https://user:password@media.example.com/song.mp3"))
        assertFalse(isSupportedDirectAudioSource("https:///song.mp3"))
    }
}
