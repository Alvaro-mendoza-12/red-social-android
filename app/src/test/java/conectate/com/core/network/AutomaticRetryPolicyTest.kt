package conectate.com.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticRetryPolicyTest {
    @Test
    fun `safe reads may retry transient failures`() {
        assertTrue(AutomaticRetryPolicy.mayRetryResponse("GET", 503))
        assertTrue(AutomaticRetryPolicy.mayRetryResponse("HEAD", 429))
    }

    @Test
    fun `mutations never retry automatically`() {
        listOf("POST", "PUT", "PATCH", "DELETE").forEach { method ->
            assertFalse(AutomaticRetryPolicy.mayRetryResponse(method, 503))
            assertFalse(AutomaticRetryPolicy.mayRetry(method))
        }
    }

    @Test
    fun `successful and client error reads are not retried`() {
        assertFalse(AutomaticRetryPolicy.mayRetryResponse("GET", 200))
        assertFalse(AutomaticRetryPolicy.mayRetryResponse("GET", 404))
    }
}
