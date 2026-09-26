package conectate.com.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VideoAccountOrderingTest {
    @Test
    fun `ordering is stable inside one account`() {
        assertEquals(
            stableAccountVideoOrder("account-a", "video-1"),
            stableAccountVideoOrder("ACCOUNT-A", "VIDEO-1"),
        )
    }

    @Test
    fun `ordering seed changes when account changes`() {
        assertNotEquals(
            stableAccountVideoOrder("account-a", "video-1"),
            stableAccountVideoOrder("account-b", "video-1"),
        )
    }

    @Test
    fun `ordering seed changes for every new feed session`() {
        assertNotEquals(
            stableAccountVideoOrder("account-a:session-1", "video-1"),
            stableAccountVideoOrder("account-a:session-2", "video-1"),
        )
    }
}
