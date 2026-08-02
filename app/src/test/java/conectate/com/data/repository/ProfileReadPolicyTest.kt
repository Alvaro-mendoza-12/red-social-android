package conectate.com.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileReadPolicyTest {

    @Test
    fun `signed-in user may use compact cache for optimistic local refresh`() {
        assertTrue(
            ProfileReadPolicy.canUseCompactCacheBeforeRemote(
                forceNetwork = false,
                requestedUserId = "user-a",
                currentUserId = "USER-A"
            )
        )
    }

    @Test
    fun `another user's compact cache never replaces complete remote profile`() {
        assertFalse(
            ProfileReadPolicy.canUseCompactCacheBeforeRemote(
                forceNetwork = false,
                requestedUserId = "user-with-music",
                currentUserId = "viewer"
            )
        )
    }

    @Test
    fun `forced refresh and missing session always require remote-first read`() {
        assertFalse(
            ProfileReadPolicy.canUseCompactCacheBeforeRemote(
                forceNetwork = true,
                requestedUserId = "user-a",
                currentUserId = "user-a"
            )
        )
        assertFalse(
            ProfileReadPolicy.canUseCompactCacheBeforeRemote(
                forceNetwork = false,
                requestedUserId = "user-a",
                currentUserId = null
            )
        )
    }
}
