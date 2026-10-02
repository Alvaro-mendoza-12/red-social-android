package conectate.com.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedCallSignalingContextTest {
    private val context = PreparedCallSignalingContext("call-1", "user-1", "https://example.test/call", "fake-token", 1_000)

    private fun matches(call: String = "call-1", user: String = "user-1",
                        endpoint: String = "https://example.test/call", token: String = "fake-token",
                        now: Long = 2_000) = context.matches(call, user, endpoint, token, now)

    @Test fun `only the same invitation and session can reuse preparation`() {
        assertTrue(matches())
        assertTrue(matches(call = "CALL-1"))
        assertFalse(matches(call = "call-2"))
        assertFalse(matches(user = "user-2"))
        assertFalse(matches(endpoint = "https://other.test/call"))
        assertFalse(matches(token = "new-token"))
        assertFalse(matches(token = ""))
    }

    @Test fun `expired preparation is not reused`() {
        assertTrue(matches(now = 45_999))
        assertFalse(matches(now = 46_000))
        assertFalse(matches(now = 46_001))
    }

    @Test fun `a monotonic clock reset invalidates preparation`() {
        assertFalse(matches(now = 999))
        assertTrue(matches(now = 1_000))
    }

    @Test fun `diagnostic output excludes authentication token`() {
        assertFalse(context.toString().contains("fake-token"))
    }
}
