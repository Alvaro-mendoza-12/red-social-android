package conectate.com.domain.sync

import org.junit.Assert.*
import org.junit.Test

class PendingSyncRetryExceptionTest {
    @Test fun `durable outbox work survives generic retry limit`() {
        for (attempt in listOf(0, 3, 20, 100)) {
            assertTrue(shouldRetrySync(PendingSyncRetryException("pending"), attempt))
        }
    }

    @Test fun `unrelated permanent failure still has bounded attempts`() {
        val failure = IllegalStateException("broken configuration")
        assertTrue(shouldRetrySync(failure, 2))
        assertFalse(shouldRetrySync(failure, 3))
    }
}
