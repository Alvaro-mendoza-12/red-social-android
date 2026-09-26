package conectate.com.presentation.voicerooms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaSourcePolicyTest {
    @Test
    fun `accepts supported public playback links`() {
        assertTrue(CinemaSourcePolicy.validateManualUrl("https://cdn.example.com/movie.mp4?token=abc").isValid)
        assertTrue(CinemaSourcePolicy.validateManualUrl("https://media.example.com/live/master.m3u8").isValid)
        assertTrue(CinemaSourcePolicy.validateManualUrl("https://drive.google.com/file/d/abc/view").isValid)
        assertTrue(CinemaSourcePolicy.validateManualUrl("https://archive.org/download/item/movie").isValid)
    }

    @Test
    fun `rejects pages credentials local targets and unsafe schemes`() {
        val rejected = listOf(
            "http://cdn.example.com/movie.mp4",
            "https://user:secret@cdn.example.com/movie.mp4",
            "https://localhost/movie.mp4",
            "https://127.0.0.1/movie.mp4",
            "https://router.local/movie.mp4",
            "https://example.com/watch/123",
            "magnet:?xt=urn:btih:ABC123",
            "file:///tmp/movie.mp4"
        )

        rejected.forEach { value ->
            assertFalse(value, CinemaSourcePolicy.validateManualUrl(value).isValid)
        }
    }
}
