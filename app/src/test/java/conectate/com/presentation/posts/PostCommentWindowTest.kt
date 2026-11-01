package conectate.com.presentation.posts

import conectate.com.data.model.Comment
import org.junit.Assert.*
import org.junit.Test

class PostCommentWindowTest {
    private fun comments(count: Int) = List(count) { i ->
        Comment("c$i", "post", "author", "Comment $i", "2026-10-31T00:00:00Z")
    }
    @Test fun fiftyOneCommentsRemainReachableThroughTheNextPage() {
        val comments = comments(51)
        val first = postCommentWindow(comments, POST_COMMENT_PAGE_SIZE)
        assertEquals(50, first.rows.size)
        assertTrue(first.hasMore)
        val next = postCommentWindow(comments, first.nextLimit)
        assertEquals(51, next.rows.size)
        assertEquals("c50", next.rows.last().first.id)
        assertFalse(next.hasMore)
    }
    @Test fun notificationTargetPastTheFirstPageIsVisibleImmediatelyAndPagingStillAdvances() {
        val comments = comments(250)
        val focused = postCommentWindow(comments, 50, "C179")
        assertEquals(180, focused.rows.size)
        assertEquals("c179", focused.rows.last().first.id)
        assertEquals(230, postCommentWindow(comments, focused.nextLimit, "c179").rows.size)
    }
    @Test fun repliesAcrossThePageBoundaryKeepTheirParentAndBecomeReachable() {
        val comments = comments(51).mapIndexed { i, comment -> if (i == 50) comment.copy(parentId = "c49") else comment }
        val first = postCommentWindow(comments, 50)
        val next = postCommentWindow(comments, first.nextLimit)
        assertEquals("c49", next.rows[49].first.id)
        assertEquals("c50", next.rows[50].first.id)
        assertEquals(1, next.rows[50].second)
    }
    @Test fun missingFocusDoesNotFabricateACommentOrAnotherPage() {
        val window = postCommentWindow(comments(2), 50, "deleted")
        assertEquals(2, window.rows.size)
        assertFalse(window.hasMore)
        assertEquals(0, postCommentWindow(emptyList(), 50).rows.size)
    }
}
