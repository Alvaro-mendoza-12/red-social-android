package conectate.com.presentation.social

import conectate.com.data.model.Post
import org.junit.Assert.*
import org.junit.Test

class SocialFeedSnapshotTest {
    private fun post(id: String, content: String = id) =
        Post(id = id, userId = "author", userName = "Author", content = content)

    @Test fun backgroundRefreshUpdatesFieldsWithoutMovingVisiblePosts() {
        val result = reconcileSocialFeed(
            listOf(post("a"), post("b")),
            listOf(post("c"), post("b", "updated"), post("a")), true,
        )
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
        assertEquals("updated", result[1].content)
    }

    @Test fun explicitRefreshUsesNewRankingAndDeduplicates() {
        val result = reconcileSocialFeed(listOf(post("a"), post("b")),
            listOf(post("b"), post("c"), post("a"), post("b")), false)
        assertEquals(listOf("b", "c", "a"), result.map { it.id })
    }

    @Test fun serverRemovalAndEmptySnapshotNeverResurrectCachedContent() {
        assertEquals(listOf("b"), reconcileSocialFeed(listOf(post("a"), post("b")),
            listOf(post("b")), true).map { it.id })
        assertTrue(reconcileSocialFeed(listOf(post("a")), emptyList(), true).isEmpty())
    }

    @Test fun firstPaintPreservesIncomingOrder() {
        val incoming = listOf(post("b"), post("a"))
        assertEquals(incoming, reconcileSocialFeed(emptyList(), incoming, true))
    }

    @Test fun impressionsOnlyResolveRealPostRows() {
        assertEquals("123", socialPostIdFromListKey("post:123"))
        assertNull(socialPostIdFromListKey("recommended-videos"))
        assertNull(socialPostIdFromListKey("post:"))
        assertNull(socialPostIdFromListKey(12))
    }
}
