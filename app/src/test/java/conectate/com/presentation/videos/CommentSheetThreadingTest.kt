package conectate.com.presentation.videos

import conectate.com.data.model.VideoComment
import org.junit.Assert.assertEquals
import org.junit.Test

class CommentSheetThreadingTest {

    @Test
    fun buildVideoCommentThread_countsNestedReplies() {
        val root = comment("A")
        val reply = comment("B", parentId = "A")
        val nestedReply = comment("C", parentId = "B")

        val thread = buildVideoCommentThread(listOf(root, reply, nestedReply))

        assertEquals(listOf("A"), thread.topLevelComments.map { it.id })
        assertEquals(listOf("B"), thread.repliesByParentId["A"]?.map { it.id })
        assertEquals(listOf("C"), thread.repliesByParentId["B"]?.map { it.id })
        assertEquals(2, thread.descendantCountByCommentId["A"])
        assertEquals(1, thread.descendantCountByCommentId["B"])
        assertEquals(0, thread.descendantCountByCommentId["C"])
    }

    @Test
    fun findVideoCommentRootId_returnsTopLevelAncestor() {
        val root = comment("A")
        val reply = comment("B", parentId = "A")
        val nestedReply = comment("C", parentId = "B")
        val commentsById = listOf(root, reply, nestedReply).associateBy { it.id }

        assertEquals("A", findVideoCommentRootId("C", commentsById))
        assertEquals("A", findVideoCommentRootId("B", commentsById))
        assertEquals("A", findVideoCommentRootId("A", commentsById))
    }

    private fun comment(id: String, parentId: String? = null): VideoComment {
        return VideoComment(
            id = id,
            userId = "user-$id",
            userName = "user$id",
            content = "comment-$id",
            parentId = parentId
        )
    }
}
