package conectate.com.data.service

import org.junit.Assert.*
import org.junit.Test

class IncomingCallRefreshPolicyTest {
    @Test fun visibleIncomingCallAcceptsItsAvatar() {
        assertTrue(canRefreshIncomingCallAvatar(true, false, false, 3_000))
    }
    @Test fun dismissedCancelledAnsweredAndExpiredCallsNeverReturn() {
        assertFalse(canRefreshIncomingCallAvatar(false, false, false, 3_000))
        assertFalse(canRefreshIncomingCallAvatar(true, true, false, 3_000))
        assertFalse(canRefreshIncomingCallAvatar(true, false, true, 3_000))
        assertFalse(canRefreshIncomingCallAvatar(true, false, false, 45_000))
        assertFalse(canRefreshIncomingCallAvatar(true, false, false, -1))
    }
}
