package conectate.com.presentation.videos

import conectate.com.data.model.VideoComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCommentStateTest {
    @Test
    fun incomingVersionReplacesStaleCommentImmediately() {
        val stale = VideoComment(id = "c1", content = "antes")
        val fresh = stale.copy(content = "después", updatedAt = "2026-07-29T12:00:00Z")

        val result = mergeLatestVideoComments(listOf(stale), listOf(fresh))

        assertEquals("después", result.single().content)
    }

    @Test
    fun reactionSwitchUpdatesCountsAndSelection() {
        val comment = VideoComment(
            id = "c1",
            reactions = mapOf("LIKE" to 2, "LOVE" to 1),
            myReaction = "LIKE"
        )

        val result = reactToVideoCommentImmediately(listOf(comment), "c1", "LOVE").single()

        assertEquals(1, result.reactions["LIKE"])
        assertEquals(2, result.reactions["LOVE"])
        assertEquals("LOVE", result.myReaction)
    }

    @Test
    fun removingReactionNeverLeavesZeroCount() {
        val comment = VideoComment(
            id = "c1",
            reactions = mapOf("LIKE" to 1),
            myReaction = "LIKE"
        )

        val result = reactToVideoCommentImmediately(listOf(comment), "c1", null).single()

        assertNull(result.reactions["LIKE"])
        assertNull(result.myReaction)
    }

    @Test
    fun editAndDeleteAreVisibleWithoutReload() {
        val comments = listOf(
            VideoComment(id = "c1", content = "original"),
            VideoComment(id = "c2", content = "otro")
        )

        val edited = editVideoCommentImmediately(comments, "c1", "editado")
        val deleted = removeVideoCommentImmediately(edited, "c1")

        assertEquals("editado", edited.first().content)
        assertEquals(listOf("c2"), deleted.map(VideoComment::id))
    }
}
