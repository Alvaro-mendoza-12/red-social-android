package conectate.com.data.repository.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxSyncPolicyTest {
    @Test
    fun `duplicate collectors do not start another fresh sync`() {
        assertFalse(
            InboxSyncPolicy.shouldStart(
                force = false,
                isRefreshing = false,
                nowMillis = 12_000L,
                lastCompletedAtMillis = 10_000L,
            )
        )
    }

    @Test
    fun `manual retry bypasses freshness but never overlaps active sync`() {
        assertTrue(
            InboxSyncPolicy.shouldStart(
                force = true,
                isRefreshing = false,
                nowMillis = 12_000L,
                lastCompletedAtMillis = 10_000L,
            )
        )
        assertFalse(
            InboxSyncPolicy.shouldStart(
                force = true,
                isRefreshing = true,
                nowMillis = 12_000L,
                lastCompletedAtMillis = 10_000L,
            )
        )
    }

    @Test
    fun `background refresh is allowed after suppression window`() {
        assertTrue(
            InboxSyncPolicy.shouldStart(
                force = false,
                isRefreshing = false,
                nowMillis = 15_000L,
                lastCompletedAtMillis = 10_000L,
            )
        )
    }

    @Test
    fun `expired remote session still returns an existing offline inbox`() {
        val result = InboxSyncPolicy.cachedOrFailure(listOf("chat-local"), IllegalStateException("JWT expired"))

        assertTrue(result.isSuccess)
        assertEquals(listOf("chat-local"), result.getOrThrow())
    }

    @Test
    fun `remote session failure remains visible when no inbox was ever cached`() {
        val failure = IllegalStateException("JWT expired")
        val result = InboxSyncPolicy.cachedOrFailure(emptyList<String>(), failure)

        assertTrue(result.isFailure)
        assertEquals(failure, result.exceptionOrNull())
    }
}
