package conectate.com.data.localfallback

import conectate.com.data.model.User
import org.junit.Assert.*
import org.junit.Test

class ProfileFollowCountMappingTest {
    @Test fun computedRelationsReplaceStaleZeroProfileColumns() {
        val user = User(id = "a", followers_count = 0, following_count = 0)
            .withComputedFollowCounts(LocalFallbackProfileStats(followers = 14, following = 8))
        assertEquals(14, user.followers_count)
        assertEquals(8, user.following_count)
    }

    @Test fun authoritativeZeroIsAcceptedRatherThanKeepingALargerCachedNumber() {
        val user = User(id = "a", followers_count = 14, following_count = 8)
            .withComputedFollowCounts(LocalFallbackProfileStats(followers = 0, following = 0))
        assertEquals(0, user.followers_count)
        assertEquals(0, user.following_count)
    }

    @Test fun profileEditWithoutStatsKeepsLatestCountersOnlyForThatProfile() {
        val edited = User(id = "a", display_name = "Nombre editado", followers_count = 0, following_count = 0)
        val previous = User(id = "a", followers_count = 14, following_count = 8)
        val merged = edited.retainingFollowCountsFrom(previous)
        assertEquals("Nombre editado", merged.display_name)
        assertEquals(14, merged.followers_count)
        assertEquals(8, merged.following_count)
        assertSame(edited, edited.retainingFollowCountsFrom(previous.copy(id = "b")))
        assertSame(edited, edited.retainingFollowCountsFrom(null))
    }
}
