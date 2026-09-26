package conectate.com.presentation.videos

import conectate.com.data.model.VideoComment
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCommentFiltersTest {
    private val comments = listOf(
        comment("early", "author", "2026-09-20T10:00:00Z", likes = 8),
        comment("late", "viewer", "2026-09-21T10:00:00Z"),
        comment("reply", "creator", "2026-09-22T10:00:00Z", parent = "early"),
        comment("nested", "me", "2026-09-23T10:00:00Z", parent = "reply"),
    )

    @Test fun recentAndRelevantHaveStableDifferentOrder() {
        assertEquals(listOf("late", "early"), view(VideoCommentOrder.RECENT).map { it.root.id })
        assertEquals(listOf("early", "late"), view(VideoCommentOrder.RELEVANT).map { it.root.id })
    }

    @Test fun creatorReplyFilterKeepsFullThreadAndNestedReply() {
        val threads = view(VideoCommentOrder.CREATOR_REPLIES)
        assertEquals(listOf("early"), threads.map { it.root.id })
        assertEquals(listOf("reply", "nested"), threads.single().replies.map { it.id })
    }

    @Test fun mineFilterKeepsParentContextWithoutShowingOtherReplies() {
        val threads = view(VideoCommentOrder.RECENT, VideoCommentAuthorFilter.MINE)
        assertEquals(listOf("early"), threads.map { it.root.id })
        assertEquals(listOf("nested"), threads.single().replies.map { it.id })
    }

    private fun view(
        order: VideoCommentOrder,
        filter: VideoCommentAuthorFilter = VideoCommentAuthorFilter.ALL,
    ) = visibleVideoCommentThreads(comments, order, filter, "me", "creator")

    private fun comment(id: String, author: String, time: String, parent: String? = null, likes: Int = 0) =
        VideoComment(id = id, userId = author, created_at = time, parentId = parent, likes = likes)
}
