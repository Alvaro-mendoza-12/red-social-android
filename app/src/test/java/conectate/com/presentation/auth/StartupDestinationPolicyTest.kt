package conectate.com.presentation.auth

import conectate.com.core.auth.isSavedGuestSessionActive

import org.junit.Assert.*
import org.junit.Test

class StartupDestinationPolicyTest {
    @Test fun registeredSessionOpensHomeEvenWithASavedGuest() {
        assertEquals(StartupDestination.HOME, startupDestination(true, false, true))
    }
    @Test fun recoveryDraftForARegisteredSessionOpensAddAccountLogin() {
        assertEquals(StartupDestination.ADD_ACCOUNT, startupDestination(true, true, true))
    }
    @Test fun recoveryDraftTakesPriorityOverASavedGuest() {
        assertEquals(StartupDestination.LOGIN, startupDestination(false, true, true))
    }
    @Test fun savedGuestCanResume() {
        assertEquals(StartupDestination.GUEST, startupDestination(false, false, true))
    }
    @Test fun signedOutWithoutGuestOpensLogin() {
        assertEquals(StartupDestination.LOGIN, startupDestination(false, false, false))
    }
    @Test fun guestRequiresATokenAndMoreThanThirtySecondsRemaining() {
        assertFalse(isSavedGuestSessionActive(false, 100_000, 0))
        assertFalse(isSavedGuestSessionActive(true, 30_000, 0))
        assertTrue(isSavedGuestSessionActive(true, 30_001, 0))
        assertFalse(isSavedGuestSessionActive(true, 1, 100))
    }
}
