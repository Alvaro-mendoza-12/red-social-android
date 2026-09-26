package conectate.com.presentation.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalDeepLinkSecurityTest {
    @Test
    fun `recovery credentials are accepted only on the dedicated custom origin`() {
        assertTrue(isTrustedRecoveryOrigin("conectate", "recovery"))
        assertTrue(isTrustedRecoveryOrigin("CONECTATE", "RECOVERY"))
        assertFalse(isTrustedRecoveryOrigin("https", "conectate.com"))
        assertFalse(isTrustedRecoveryOrigin("evil", "recovery"))
    }

    @Test
    fun `external deep links reject unknown origins and custom hosts`() {
        assertTrue(isTrustedExternalDeepLinkOrigin("https", "conectate.com"))
        assertTrue(isTrustedExternalDeepLinkOrigin("conectate", "video"))
        assertFalse(isTrustedExternalDeepLinkOrigin("https", "evil.example"))
        assertFalse(isTrustedExternalDeepLinkOrigin("conectate", "unknown-action"))
        assertFalse(isTrustedExternalDeepLinkOrigin("javascript", "conectate.com"))
    }

    @Test
    fun `external route values are bounded and reject control characters`() {
        assertTrue(safeExternalRouteValue(" video-123 ") == "video-123")
        assertNull(safeExternalRouteValue(""))
        assertNull(safeExternalRouteValue("x".repeat(161)))
        assertNull(safeExternalRouteValue("video\n123"))
    }
}
