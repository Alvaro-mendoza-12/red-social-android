package conectate.com.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PostMediaTypeTest {
    @Test fun `signed mp4 remains a video and never enters an image decoder`() {
        assertEquals("video", postMediaType("https://media.example/file.MP4?signature=private#t=4"))
    }
    @Test fun `video extension inside a photo query does not change the type`() {
        assertEquals("image", postMediaType("https://media.example/photo.jpg?caption=clip.mp4"))
    }
    @Test fun `mixed galleries retain image and video item types`() {
        assertEquals(listOf("image", "video", "image"), listOf("a.webp", "b.webm", "c.png").map(::postMediaType))
    }
}
