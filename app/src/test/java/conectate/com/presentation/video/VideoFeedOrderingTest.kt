package conectate.com.presentation.video

import conectate.com.data.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFeedOrderingTest {
    @Test
    fun `same session remains stable`() {
        val videos = listOf(Video(id = "a"), Video(id = "b"), Video(id = "c"))

        val first = orderVideosForFeedSession(videos, "account-a", "session-1")
        val second = orderVideosForFeedSession(videos.reversed(), "account-a", "session-1")

        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `new session does not repeat the complete previous order`() {
        val videos = listOf(Video(id = "a"), Video(id = "b"), Video(id = "c"))
        val previous = orderVideosForFeedSession(videos, "account-a", "session-1")
        val next = orderVideosForFeedSession(
            videos,
            "account-a",
            "session-2",
            previousOrder = previous.map { it.id.trim().lowercase() },
        )

        assertNotEquals(previous.map { it.id }, next.map { it.id })
        assertNotEquals(previous.first().id, next.first().id)
        assertEquals(videos.map { it.id }.toSet(), next.map { it.id }.toSet())
    }

    @Test
    fun `duplicates are removed without changing eligibility`() {
        val videos = listOf(Video(id = "a"), Video(id = "A"), Video(id = "b"))

        val result = orderVideosForFeedSession(videos, "account-a", "session-1")

        assertEquals(2, result.size)
        assertTrue(result.map { it.id.lowercase() }.toSet() == setOf("a", "b"))
    }

    @Test
    fun `authoritative pages preserve server ranking`() {
        val videos = listOf(Video(id = "rank-1"), Video(id = "rank-2"), Video(id = "rank-3"))

        val result = orderVideosForFeedSession(
            videos = videos,
            accountId = "account-a",
            sessionId = "session-1",
            preserveSourceOrder = true,
        )

        assertEquals(videos.map { it.id }, result.map { it.id })
    }
}
