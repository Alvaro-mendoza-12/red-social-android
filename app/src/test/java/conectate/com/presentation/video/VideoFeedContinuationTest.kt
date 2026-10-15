package conectate.com.presentation.video

import org.junit.Assert.*
import org.junit.Test

class VideoFeedContinuationTest {
    private val tail = VideoFeedTailEntry.stableKey
    private fun seeded(ids: List<String> = listOf("v1", "v2")) =
        VideoFeedTailAppendAnchor().also { assertNull(it.observe("session", ids, tail, false)) }

    @Test fun tailAdvancesToFirstActuallyAddedVideo() {
        val anchor = seeded()
        assertEquals("v3", anchor.observe("session", listOf("v1", "v2", "v3", "v4"), tail, false))
        assertNull(anchor.observe("session", listOf("v1", "v2", "v3", "v4"), tail, false))
    }

    @Test fun lateRankingKeepsActiveVideoSelection() {
        assertNull(seeded().observe("session", listOf("v3", "v2", "v1"), "video:v2", false))
    }

    @Test fun reorderingExistingVideosDoesNotAdvanceTail() {
        assertNull(seeded().observe("session", listOf("v2", "v1"), tail, false))
    }

    @Test fun duplicateOrCounterOnlyUpdatesCannotPretendToAddVideo() {
        assertNull(seeded().observe("session", listOf("V1", "v2", " v2 "), tail, false))
    }

    @Test fun anotherSessionAndDisabledFeedsDoNotInheritTailAnchor() {
        assertNull(seeded().observe("another-session", listOf("v1", "v2", "v3"), tail, false))
        assertNull(seeded().observe("session", listOf("v1", "v2", "v3"), tail, false, enabled = false))
    }

    @Test fun gestureSettlesBeforeAdmissionAndRetainsFirstNewVideoAcrossPages() {
        val anchor = seeded()
        assertNull(anchor.observe("session", listOf("v1", "v2", "v3"), tail, true))
        assertNull(anchor.observe("session", listOf("v1", "v2", "v3", "v4"), tail, true))
        assertEquals("v3", anchor.observe("session", listOf("v1", "v2", "v3", "v4"), tail, false))
    }

    @Test fun leavingTailDuringGesturePreservesTheChosenVideo() {
        val anchor = seeded()
        val ids = listOf("v1", "v2", "v3")
        assertNull(anchor.observe("session", ids, tail, true))
        assertNull(anchor.observe("session", ids, "video:v2", false))
        assertNull(anchor.observe("session", ids, tail, false))
    }

    @Test fun acceptedEmptyLegacyContinuationStillShowsCatalogError() {
        assertEquals("catalog failed", videoFeedActiveSourceError(false, "catalog failed", "stale recommendation error"))
    }

    @Test fun activePersonalizedFeedIgnoresBackgroundCatalogFailure() {
        assertNull(videoFeedActiveSourceError(true, "catalog failed", null))
    }

    @Test fun activePersonalizedContinuationShowsItsOwnRetryError() {
        assertEquals("page failed", videoFeedActiveSourceError(true, "catalog failed", "page failed"))
    }
}
