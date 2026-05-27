package conectate.com.util

import conectate.com.data.model.GroupMessage
import conectate.com.data.local.entity.GroupMessageEntity
import conectate.com.data.local.entity.CommunityMessageEntity
import conectate.com.data.model.CommunityMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreFormattingTest {

    @Test
    fun `formatRelativeDate returns fallback for null timestamp`() {
        val result = DateUtils.formatRelativeDate(null)
        assertEquals("Recientemente", result)
    }

    @Test
    fun `formatRelativeDate returns fallback for invalid timestamp`() {
        val result = DateUtils.formatRelativeDate("no-es-fecha")
        assertEquals("Hace un momento", result)
    }

    @Test
    fun `group message sort key normalizes blank spaces`() {
        val message = GroupMessage(created_at = "2026-01-01 12:00:00")
        assertEquals("2026-01-01T12:00:00", message.sortKey)
    }

    @Test
    fun `group message timestamp parses iso format`() {
        val message = GroupMessage(created_at = "2026-01-01T00:00:00Z")
        assertEquals(1767225600000L, message.timestampMillis)
    }

    @Test
    fun `group message timestamp falls back when malformed`() {
        val message = GroupMessage(created_at = "123bad")
        assertTrue(message.timestampMillis > 0L)
    }

    @Test
    fun `group message entity stores pending sync metadata`() {
        val domain = GroupMessage(
            id = "m1",
            group_id = "g1",
            sender_id = "u1",
            content = "hola",
            created_at = "2026-01-01T00:00:00Z",
            status = "sending"
        )

        val entity = GroupMessageEntity.fromDomain(
            msg = domain,
            isSynced = false,
            pendingAction = "send",
            retryCount = 2,
            lastRetryAt = "2026-01-01T00:01:00Z"
        )

        assertEquals(false, entity.is_synced)
        assertEquals("send", entity.pending_action)
        assertEquals(2, entity.retry_count)
        assertEquals("2026-01-01T00:01:00Z", entity.last_retry_at)
    }

    @Test
    fun `community message entity stores pending sync metadata`() {
        val domain = CommunityMessage(
            id = "cm1",
            community_id = "c1",
            sender_id = "u1",
            content = "hola comunidad",
            created_at = "2026-01-01T00:00:00Z"
        )

        val entity = CommunityMessageEntity.fromDomain(
            msg = domain,
            isSynced = false,
            pendingAction = "send",
            retryCount = 3,
            lastRetryAt = "2026-01-01T00:02:00Z"
        )

        assertEquals(false, entity.is_synced)
        assertEquals("send", entity.pending_action)
        assertEquals(3, entity.retry_count)
        assertEquals("2026-01-01T00:02:00Z", entity.last_retry_at)
    }
}
