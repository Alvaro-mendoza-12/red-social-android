package conectate.com.presentation.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPairingApprovalPolicyTest {
    @Test fun localServerApprovesWithItsValidAccessSessionWithoutAnUnneededRefreshToken() {
        assertTrue(webPairingApprovalSessionValid("valid-access", "", false))
    }
    @Test fun legacySupabaseApprovalStillRequiresBothTokens() {
        assertFalse(webPairingApprovalSessionValid("valid-access", "", true))
        assertTrue(webPairingApprovalSessionValid("valid-access", "valid-refresh", true))
    }
    @Test fun guestOrEmptySessionsCannotApproveTheBrowser() {
        assertFalse(webPairingApprovalSessionValid("", "valid-refresh", false))
        assertFalse(webPairingApprovalSessionValid(" ", "valid-refresh", true))
    }
}
