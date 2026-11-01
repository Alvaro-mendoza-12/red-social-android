package conectate.com.presentation.posts

import conectate.com.data.model.Comment
import org.junit.Assert.*
import org.junit.Test

class PostCommentThreadsTest {
    private fun comment(id: String, parent: String? = null) = Comment(id, "post", "author", "Comment $id", "2026-10-31T00:00:00Z", parentId = parent)
    @Test fun parentsAndAllNestedRepliesRemainInOrder() {
        val rows = listOf(comment("root"), comment("child", "root"), comment("grandchild", "child"), comment("next"))
        assertEquals(listOf("root", "child", "grandchild", "next"), organizeThreaded(rows).map { it.first.id })
        assertEquals(listOf(0, 1, 2, 0), organizeThreaded(rows).map { it.second })
    }
    @Test fun missingOrDeletedParentsDoNotHideTheirAvailableReplies() {
        assertEquals(listOf("orphan", "reply"), organizeThreaded(listOf(comment("orphan", "missing"), comment("reply", "orphan"))).map { it.first.id })
    }
    @Test fun duplicatesAndCyclesCannotHideCommentsOrLoopForever() {
        val rows = listOf(comment("self", "self"), comment("a", "b"), comment("b", "a"), comment("a", "b"))
        assertEquals(setOf("self", "a", "b"), organizeThreaded(rows).map { it.first.id }.toSet())
        assertEquals(3, organizeThreaded(rows).size)
    }
    @Test fun longThreadsAreIterativeAndHaveBoundedIndentation() {
        val rows = List(10_000) { i -> comment("c$i", if (i == 0) null else "c${i-1}") }
        val result = organizeThreaded(rows)
        assertEquals(10_000, result.size)
        assertTrue(result.all { it.second in 0..3 })
    }
}
