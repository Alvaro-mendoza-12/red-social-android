package conectate.com.presentation.video

import conectate.com.data.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFeedOrderingTest {
    @Test fun `late ranking keeps displayed prefix and reorders only unseen items`() {
        val current = listOf("a", "b", "c", "d").map { Video(id = it) }
        val ranked = listOf("d", "c", "b", "a", "e").map { Video(id = it) }
        val result = mergeRankedPageAfterDisplayedPrefix(current, ranked, setOf("b"))
        assertEquals(listOf("a", "b", "d", "c", "e"), result.map { it.id })
    }

    @Test fun `ranking arriving before playback uses the personalized order`() {
        val current = listOf(Video(id = "a"), Video(id = "b"))
        val ranked = listOf(Video(id = "b"), Video(id = "a"))
        assertEquals(ranked, mergeRankedPageAfterDisplayedPrefix(current, ranked, emptySet()))
    }

    @Test fun `fallback order varies between accounts for the same session`() {
        val videos = (1..20).map { Video(id = "video-$it") }
        val orders = (1..3).map { account ->
            orderVideosForFeedSession(videos, "account-$account", "same-session").map { it.id }
        }
        assertEquals(3, orders.toSet().size)
    }
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
