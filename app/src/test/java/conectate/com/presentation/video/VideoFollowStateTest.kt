package conectate.com.presentation.video

import org.junit.Assert.*
import org.junit.Test

class VideoFollowStateTest {
    @Test
    fun `a response started before following cannot undo a confirmed follow`() {
        val before = VideoFollowState()
        val confirmed = before.begin(" A ").complete("a", true)
        val after = confirmed.reconcile(listOf("a"), emptySet(), before.revision)
        assertEquals(setOf("a"), after.followed)
        assertTrue(after.pending.isEmpty())
    }

    @Test
    fun `a response started during following cannot undo its confirmation`() {
        val pending = VideoFollowState().begin("a")
        val confirmed = pending.complete("a", true)
        assertEquals(setOf("a"), confirmed.reconcile(listOf("a"), emptySet(), pending.revision).followed)
    }

    @Test
    fun `failure restores the plus without affecting other followed users`() {
        val state = VideoFollowState(followed = setOf("existing")).begin("a")
        assertSame(state, state.begin(" A "))
        val failed = state.complete("a", false)
        assertEquals(setOf("existing"), failed.followed)
        assertTrue(failed.pending.isEmpty())
    }

    @Test
    fun `fresh status reflects external unfollow and keeps offscreen and pending users`() {
        val state = VideoFollowState(followed = setOf("visible", "offscreen")).begin("pending")
        val updated = state.reconcile(listOf("visible", "pending", "new"), setOf("NEW"), state.revision)
        assertEquals(setOf("offscreen", "pending", "new"), updated.followed)
    }
}
