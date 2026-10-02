package conectate.com.data.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedIceReadinessTest {
    @Test fun missingServersWaitEvenIfAnExpiryWasCached() {
        assertTrue(shouldAwaitManagedIceRefresh(false, 2_000, 1_000))
    }
    @Test fun expiredCredentialsWaitAtTheExpiryBoundary() {
        assertTrue(shouldAwaitManagedIceRefresh(true, 1_000, 1_000))
        assertTrue(shouldAwaitManagedIceRefresh(true, 999, 1_000))
    }
    @Test fun validCredentialsDoNotWaitForBackgroundRenewal() {
        assertFalse(shouldAwaitManagedIceRefresh(true, 1_001, 1_000))
    }
    @Test fun serversWithoutAnExpiryRemainCompatible() {
        assertFalse(shouldAwaitManagedIceRefresh(true, 0, 1_000))
        assertTrue(shouldAwaitManagedIceRefresh(false, 0, 1_000))
    }

    @Test fun reconnectReusesValidCredentials() {
        assertFalse(shouldRefreshManagedIce(false, true, 2_000, 1_000))
    }

    @Test fun ordinaryRefreshRenewsMissingOrExpiredCredentials() {
        assertTrue(shouldRefreshManagedIce(false, false, 2_000, 1_000))
        assertTrue(shouldRefreshManagedIce(false, true, 1_000, 1_000))
        assertTrue(shouldRefreshManagedIce(false, true, 999, 1_000))
    }

    @Test fun recoveryCanForceRefreshWhileCredentialsRemainValid() {
        assertTrue(shouldRefreshManagedIce(true, true, 2_000, 1_000))
    }

    @Test fun accountChangeDiscardsCredentialsButFirstBindingAndSameAccountDoNot() {
        assertFalse(shouldDiscardManagedIceForAccountChange("", "account-a"))
        assertFalse(shouldDiscardManagedIceForAccountChange("account-a", "ACCOUNT-A"))
        assertTrue(shouldDiscardManagedIceForAccountChange("account-a", "account-b"))
        assertTrue(shouldDiscardManagedIceForAccountChange("account-a", ""))
    }

    @Test fun turnResponsesMustMatchBothSessionGenerationAndToken() {
        assertTrue(acceptsManagedIceResponse(2, 2, "fixture-token", "fixture-token"))
        assertFalse(acceptsManagedIceResponse(1, 2, "fixture-token", "fixture-token"))
        assertFalse(acceptsManagedIceResponse(2, 2, "previous-token", "fixture-token"))
        assertFalse(acceptsManagedIceResponse(2, 2, "", ""))
    }
}
