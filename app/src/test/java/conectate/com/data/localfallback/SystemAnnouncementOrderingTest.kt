package conectate.com.data.localfallback

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SystemAnnouncementOrderingTest {
    private val now = Instant.parse("2026-10-03T12:00:00Z")
    private fun notice(id: String, kind: String = "info") = SystemAnnouncement(
        id, kind, "Aviso", "Mensaje", "2026-10-10T12:00:00Z", versionCode = 118,
        createdAt = "2026-10-02T12:00:00Z",
    )
    @Test fun `active maintenance upcoming maintenance update and news have a predictable order`() {
        val active = notice("active", "maintenance").copy(startsAt = "2026-10-03T11:00:00Z", endsAt = "2026-10-03T13:00:00Z")
        val future = notice("future", "maintenance").copy(startsAt = "2026-10-04T11:00:00Z", endsAt = "2026-10-04T13:00:00Z")
        assertEquals(listOf("active", "future", "update", "news"),
            orderedSystemAnnouncements(listOf(notice("news"), notice("update", "update"), future, active), 117, now).map { it.id })
    }
    @Test fun `withdrawn expired and installed updates never enter the inbox`() {
        assertTrue(orderedSystemAnnouncements(listOf(
            notice("revoked").copy(revokedAt = now.toString()),
            notice("expired").copy(expiresAt = now.toString()),
            notice("installed", "update").copy(versionCode = 117),
        ), 117, now).isEmpty())
    }
    @Test fun `newest news appears first and duplicates do not repeat`() {
        val newer = notice("new").copy(createdAt = now.toString())
        assertEquals(listOf("new", "old"), orderedSystemAnnouncements(listOf(notice("old"), newer, newer), 117, now).map { it.id })
    }
    @Test fun `malformed creation date does not crash or precede a dated notice`() {
        val missing = notice("unknown").copy(createdAt = "invalid")
        assertEquals(listOf("known", "unknown"), orderedSystemAnnouncements(listOf(missing, notice("known")), 117, now).map { it.id })
    }
}
