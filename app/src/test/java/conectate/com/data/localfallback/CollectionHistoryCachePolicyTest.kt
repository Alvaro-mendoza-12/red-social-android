package conectate.com.data.localfallback

import org.junit.Assert.*
import org.junit.Test

class CollectionHistoryCachePolicyTest {
    private val grant = CollectionHistoryAccess(true, false, "2026-06-01T00:00:00Z", "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z")
    @Test fun rejoinDropsPreviousGrant() {
        val access = resolveCachedCollectionHistory(grant, "2026-08-01T00:00:00Z", false, true)
        assertFalse(access.allows("2026-01-15T00:00:00Z"))
        assertFalse(access.allows("2026-07-01T00:00:00Z"))
        assertTrue(access.allows("2026-08-01T00:00:00Z"))
    }
    @Test fun sameEpochPreservesOfflineGrant() {
        assertEquals(grant, resolveCachedCollectionHistory(grant, "2026-06-01T00:00:00.000Z", false, true))
    }
    @Test fun removedMemberCannotReadCachedMessages() {
        assertFalse(resolveCachedCollectionHistory(grant, grant.joinedAt, true, false).allows("2026-01-15T00:00:00Z"))
    }
    @Test fun departedServerReceiptSurvivesOfflineAndRestart() {
        val archived = CollectionHistoryAccess(canReadHistory = true, retainedRanges = listOf(
            CollectionHistoryRange(grant.joinedAt, "2026-07-01T00:00:00Z")))
        assertEquals(archived, resolveCachedCollectionHistory(archived, null, false, false))
        assertTrue(resolveCachedCollectionHistory(archived, null, false, false).allows("2026-06-15T00:00:00Z"))
        assertFalse(resolveCachedCollectionHistory(archived, null, false, false).allows("2026-07-02T00:00:00Z"))
    }
    @Test fun ownershipTransferKeepsAuthoritativeLimitedHistory() {
        assertEquals(grant, resolveCachedCollectionHistory(grant, grant.joinedAt, true, true))
        assertFalse(resolveCachedCollectionHistory(grant, grant.joinedAt, true, true).allows("2026-05-01T00:00:00Z"))
    }
    @Test fun missingMembershipFailsClosedAndCreatorKeepsHistory() {
        assertFalse(resolveCachedCollectionHistory(null, null, false, true).allows("2026-01-15T00:00:00Z"))
        assertTrue(resolveCachedCollectionHistory(null, null, true, true).allows("2026-01-15T00:00:00Z"))
    }
}
