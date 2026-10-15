package conectate.com.data.localfallback

import org.junit.Assert.*
import org.junit.Test

class CollectionHistoryAccessTest {
    private val access = CollectionHistoryAccess(true, false, "2026-06-01T12:00:00Z", "2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z")
    @Test fun newMemberCannotSeeEarlierMessages() {
        assertFalse(access.copy(historyFrom = null, historyUntil = null).allows("2026-06-01T11:59:59.999Z"))
        assertTrue(access.allows("2026-06-01T12:00:00Z"))
        assertTrue(access.allows("2027-01-01T00:00:00Z"))
    }
    @Test fun earlierGrantIsBoundedAndDoesNotBridgeTheGap() {
        assertFalse(access.allows("2026-02-28T23:59:59Z"))
        assertTrue(access.allows("2026-03-01T00:00:00Z"))
        assertTrue(access.allows("2026-04-01T00:00:00Z"))
        assertFalse(access.allows("2026-04-01T00:00:00.001Z"))
    }
    @Test fun nonmemberAndMissingEpochFailClosed() {
        assertFalse(access.copy(isMember = false, allHistory = true).allows("2026-06-01T12:00:00Z"))
        assertFalse(access.copy(joinedAt = null).allows("2026-06-01T12:00:00Z"))
        assertFalse(access.allows("invalid"))
    }
    @Test fun departedMemberRetainsOnlyAuthorizedIntervals() {
        val departed = CollectionHistoryAccess(canReadHistory = true, retainedRanges = listOf(
            CollectionHistoryRange("2026-03-01T00:00:00Z", "2026-04-01T00:00:00Z"),
            CollectionHistoryRange("2026-06-01T00:00:00Z", "2026-08-01T00:00:00Z")))
        assertTrue(departed.allows("2026-03-15T00:00:00Z"))
        assertTrue(departed.allows("2026-07-01T00:00:00Z"))
        assertFalse(departed.allows("2026-05-01T00:00:00Z"))
        assertFalse(departed.allows("2026-08-01T00:00:00.001Z"))
        assertFalse(departed.allows("invalid"))
        val rejoined = departed.copy(isMember = true, joinedAt = "2026-09-01T00:00:00Z")
        assertTrue(rejoined.allows("2026-07-01T00:00:00Z"))
        assertFalse(rejoined.allows("2026-08-15T00:00:00Z"))
    }
    @Test fun creatorCanSeeHistory() {
        assertTrue(access.copy(allHistory = true).allows("2026-01-01T00:00:00Z"))
    }
}
