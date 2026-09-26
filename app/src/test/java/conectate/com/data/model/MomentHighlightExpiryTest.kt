package conectate.com.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MomentHighlightExpiryTest {
    private val now = Instant.parse("2026-08-13T20:00:00Z")

    @Test
    fun activeMomentRemainsInHighlightUntilItsOwnExpiration() {
        assertTrue(item("2026-08-13T20:00:01Z").isActiveAt(now))
    }

    @Test
    fun expiredMomentIsRemovedFromHighlightAtSameDeadline() {
        assertFalse(item("2026-08-13T20:00:00Z").isActiveAt(now))
        assertFalse(item("2026-08-13T19:59:59Z").isActiveAt(now))
    }

    @Test
    fun malformedExpirationFailsClosed() {
        assertFalse(item("not-a-date").isActiveAt(now))
    }

    private fun item(expiresAt: String?) = MomentHighlightItemPreview(
        id = "item",
        highlightId = "highlight",
        momentId = "moment",
        mediaUrl = "https://example.com/moment.jpg",
        mediaType = "image",
        expiresAt = expiresAt,
    )
}
