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

    @Test fun aPartialPageKeepsRepliesBelowTheirClosestAvailableAncestor() {
        val availableParent = comments[2].copy(parentId = "missing")
        val threads = visibleVideoCommentThreads(
            listOf(comments[3], availableParent),
            VideoCommentOrder.RECENT, VideoCommentAuthorFilter.MINE, "me", "creator",
        )
        assertEquals(listOf("reply"), threads.map { it.root.id })
        assertEquals(listOf("nested"), threads.single().replies.map { it.id })
    }

    @Test fun duplicateAliasesDoNotBecomeAdditionalReplies() {
        val duplicateReply = comments[2].copy(id = " REPLY ")
        val threads = visibleVideoCommentThreads(
            comments + duplicateReply, VideoCommentOrder.RECENT,
            VideoCommentAuthorFilter.ALL, "me", "creator",
        )
        assertEquals(listOf("reply", "nested"), threads.single { it.root.id == "early" }.replies.map { it.id })
    }

    @Test fun aHistoricalCycleUsesOneStableRootAndKeepsItsDescendants() {
        val first = comments[0].copy(parentId = "reply")
        val second = comments[2]
        val descendant = comments[3]
        val cyclic = listOf(first, second, descendant)
        for (input in listOf(cyclic, cyclic.reversed())) {
            val thread = visibleVideoCommentThreads(
                input, VideoCommentOrder.RECENT, VideoCommentAuthorFilter.ALL, "me", "creator",
            ).single()
            assertEquals("early", thread.root.id)
            assertEquals(listOf("reply", "nested"), thread.replies.map { it.id })
        }
    }

    private fun view(
        order: VideoCommentOrder,
        filter: VideoCommentAuthorFilter = VideoCommentAuthorFilter.ALL,
    ) = visibleVideoCommentThreads(comments, order, filter, "me", "creator")

    private fun comment(id: String, author: String, time: String, parent: String? = null, likes: Int = 0) =
        VideoComment(id = id, userId = author, created_at = time, parentId = parent, likes = likes)
}
