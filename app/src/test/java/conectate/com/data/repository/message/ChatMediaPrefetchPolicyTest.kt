package conectate.com.data.repository.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaPrefetchPolicyTest {

    @Test
    fun `selects recent ordinary media and removes duplicate urls`() {
        val selected = selectRecentChatMediaForPrefetch(
            candidates = listOf(
                candidate("text", null),
                candidate("image", " https://cdn.test/photo.jpg "),
                candidate("audio", "https://cdn.test/voice.m4a"),
                candidate("image", "https://cdn.test/photo.jpg"),
                candidate("sticker", "https://cdn.test/sticker.webp")
            ),
            maxItems = 2
        )

        assertEquals(
            listOf("https://cdn.test/voice.m4a", "https://cdn.test/sticker.webp"),
            selected
        )
    }

    @Test
    fun `never prefetches view once media`() {
        val selected = selectRecentChatMediaForPrefetch(
            listOf(
                candidate("view_once_image", "https://cdn.test/private.jpg"),
                candidate("view_once_video", "https://cdn.test/private.mp4")
            )
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `known oversized videos remain stream only while unknown sizes stay eligible`() {
        val selected = selectRecentChatMediaForPrefetch(
            candidates = listOf(
                candidate("video", "https://cdn.test/large.mp4", 13L * 1024L * 1024L),
                candidate("video", "https://cdn.test/group-video.mp4", null),
                candidate("video", "https://cdn.test/small.mp4", 4L * 1024L * 1024L)
            ),
            maxVideoBytes = 12L * 1024L * 1024L
        )

        assertEquals(
            listOf("https://cdn.test/group-video.mp4", "https://cdn.test/small.mp4"),
            selected
        )
    }

    private fun candidate(type: String, url: String?, size: Long? = null) =
        ChatMediaPrefetchCandidate(type, url, size)
}
