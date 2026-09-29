package conectate.com.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveAudienceStatsTest {
    @Test fun `peak remains after viewers leave`() {
        val stats = LiveAudienceStats().withViewerCount(1).withViewerCount(4).withViewerCount(0)
        assertEquals(4, stats.peakConcurrent)
    }

    @Test fun `same account reconnecting is one unique viewer`() {
        val stats = LiveAudienceStats()
            .withViewers(listOf(LiveViewerParticipant(socketId = "first", userId = "USER", name = "A")))
            .withViewers(listOf(LiveViewerParticipant(socketId = "second", userId = "user", name = "A")))
        assertEquals(1, stats.uniqueViewers)
    }

    @Test fun `departed viewers remain in unique audience`() {
        val stats = LiveAudienceStats()
            .withViewers(listOf(LiveViewerParticipant(socketId = "a", userId = "a", name = "A")))
            .withViewers(emptyList())
            .withViewers(listOf(LiveViewerParticipant(socketId = "b", userId = "b", name = "B")))
        assertEquals(2, stats.uniqueViewers)
    }
}
