package conectate.com.presentation.home

import conectate.com.data.model.PlayMatchConnection
import conectate.com.data.model.PlayMatchMatch
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HomeNoveltyPolicyTest {
    private val now = Instant.parse("2026-09-28T12:00:00Z")

    @Test fun `malformed timestamps never invent a novelty`() {
        assertNull(HomeNoveltyPolicy.newest(listOf(null, "", "invalid")))
        assertFalse(HomeNoveltyPolicy.hasNewer(null, now))
    }

    @Test fun `latest loaded item does not acknowledge a later arrival`() {
        val loaded = HomeNoveltyPolicy.newest(listOf("2026-09-28T11:00:00Z", now.toString()))!!
        assertEquals(now, loaded)
        assertTrue(HomeNoveltyPolicy.hasNewer(now.plusSeconds(1), loaded))
        assertFalse(HomeNoveltyPolicy.hasNewer(now, loaded))
    }

    @Test fun `only incoming invitations and accepted connections need attention`() {
        val keys = HomeNoveltyPolicy.gameEvents(listOf(
            PlayMatchConnection("incoming", "PENDING", "INCOMING"),
            PlayMatchConnection("outgoing", "PENDING", "OUTGOING"),
            PlayMatchConnection("accepted", "ACCEPTED", "OUTGOING"),
            PlayMatchConnection("rejected", "REJECTED", "INCOMING"),
        ), emptyList(), now)
        assertEquals(setOf("connection:incoming:PENDING:", "connection:accepted:ACCEPTED:"), keys)
    }

    @Test fun `expired cancelled or undated pending matches do not create dots`() {
        val keys = HomeNoveltyPolicy.gameEvents(emptyList(), listOf(
            PlayMatchMatch("valid", expiresAt = now.plusSeconds(60).toString()),
            PlayMatchMatch("expired", expiresAt = now.minusSeconds(1).toString()),
            PlayMatchMatch("undated"),
            PlayMatchMatch("cancelled", "CANCELLED", expiresAt = now.plusSeconds(60).toString()),
            PlayMatchMatch("accepted", "ACCEPTED"),
        ), now)
        assertEquals(setOf("match:valid:PENDING", "match:accepted:ACCEPTED"), keys)
    }

    @Test fun `a replacement or status change remains unseen after reading an older event`() {
        val old = HomeNoveltyPolicy.gameEvents(listOf(PlayMatchConnection("same", "PENDING", "INCOMING", updatedAt = "old")), emptyList(), now)
        val new = HomeNoveltyPolicy.gameEvents(listOf(PlayMatchConnection("same", "ACCEPTED", "INCOMING", updatedAt = "new")), emptyList(), now)
        assertTrue((new - old).isNotEmpty())
        assertTrue((new - new).isEmpty())
    }
}
