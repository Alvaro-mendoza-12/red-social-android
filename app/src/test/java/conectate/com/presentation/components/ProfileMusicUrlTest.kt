package conectate.com.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileMusicUrlTest {
    @Test
    fun `youtube watch link resolves to embedded player id`() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun `youtube short and share links resolve to embedded player id`() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?t=5")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://youtube.com/shorts/dQw4w9WgXcQ")
        )
    }

    @Test
    fun `youtube music link resolves to embedded player id`() {
        assertEquals(
            "dQw4w9WgXcQ",
            extractYouTubeVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }

    @Test
    fun `direct audio link remains handled by native audio player`() {
        assertNull(extractYouTubeVideoId("https://media.example.com/song.mp3"))
    }
}
