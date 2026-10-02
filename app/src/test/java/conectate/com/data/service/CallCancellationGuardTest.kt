package conectate.com.data.service

import org.junit.Assert.*
import org.junit.Test

class CallCancellationGuardTest {
    @Test fun lateInvitationsStayCancelledWithinTheirLifetime() {
        assertTrue(isRecentCallCancellation(1_000_000, 1_000_001))
        assertTrue(isRecentCallCancellation(1_000_000, 1_120_000))
        assertFalse(isRecentCallCancellation(1_000_000, 1_120_001))
        assertFalse(isRecentCallCancellation(0, 1_000_000))
    }
    @Test fun aSmallClockCorrectionCannotResurrectTheCall() {
        assertTrue(isRecentCallCancellation(1_000_000, 999_000))
        assertFalse(isRecentCallCancellation(1_000_000, 879_999))
    }
}
