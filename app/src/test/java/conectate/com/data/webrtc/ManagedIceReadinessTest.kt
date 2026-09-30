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
}
