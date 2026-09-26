package conectate.com.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRecoveryPolicyTest {
    @Test
    fun `retries only an online failed empty first page`() {
        assertTrue(FeedRecoveryPolicy.shouldRetry(true, false, false, true, 0))
        assertFalse(FeedRecoveryPolicy.shouldRetry(true, true, false, true, 0))
        assertFalse(FeedRecoveryPolicy.shouldRetry(false, false, false, true, 0))
        assertFalse(FeedRecoveryPolicy.shouldRetry(true, false, true, true, 0))
        assertFalse(FeedRecoveryPolicy.shouldRetry(true, false, false, false, 0))
    }

    @Test
    fun `automatic retries are bounded and back off`() {
        assertEquals(1_500L, FeedRecoveryPolicy.delayMillis(0))
        assertEquals(3_000L, FeedRecoveryPolicy.delayMillis(1))
        assertFalse(
            FeedRecoveryPolicy.shouldRetry(
                isConnected = true,
                hasContent = false,
                isLoading = false,
                hasError = true,
                attempts = FeedRecoveryPolicy.MaxAutomaticAttempts,
            )
        )
    }
}
