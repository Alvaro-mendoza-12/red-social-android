package conectate.com.data.localfallback

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class SystemAnnouncementTest {
    private val now = Instant.parse("2026-10-01T20:00:00Z")
    private fun notice(kind: String = "info", version: Int? = null) =
        SystemAnnouncement("a", kind, "Aviso", "Mensaje del sistema", "2026-10-02T20:00:00Z", versionCode = version)
    @Test fun `update is visible only for an older installed version`() {
        assertTrue(notice("update", 118).isVisible(117, now))
        assertFalse(notice("update", 118).isVisible(118, now))
        assertFalse(notice("update", 118).isVisible(119, now))
        assertFalse(notice("update").isVisible(117, now))
    }
    @Test fun `expired revoked and malformed cached notices cannot reappear`() {
        assertTrue(notice().isVisible(117, now))
        assertFalse(notice().copy(revokedAt = now.toString()).isVisible(117, now))
        assertFalse(notice().isVisible(117, Instant.parse("2026-10-03T20:00:00Z")))
        assertFalse(notice().copy(expiresAt = "invalid").isVisible(117, now))
    }
}
