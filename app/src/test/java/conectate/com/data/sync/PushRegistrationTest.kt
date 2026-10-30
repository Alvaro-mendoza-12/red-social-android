package conectate.com.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PushRegistrationTest {
    @Test fun noAccountDoesNotFetchOrSendAToken() = runTest {
        val result = registerCurrentPushToken({ null }, { error("No token request expected") }, { error("No registration expected") })
        assertTrue(result.isSuccess)
    }

    @Test fun accountChangeWhileFirebaseResolvesDoesNotRegisterTheOldRequest() = runTest {
        var account = "account-a"
        val result = registerCurrentPushToken({ account }, { account = "account-b"; "firebase-token" }, { error("Obsolete registration") })
        assertTrue(result.isSuccess)
    }

    @Test fun aRetryResolvesTheLatestTokenInsteadOfPersistingAnOldOne() = runTest {
        val sent = mutableListOf<String>()
        var token = "old"
        assertTrue(registerCurrentPushToken({ "a" }, { token }, { sent += it; Result.failure(java.io.IOException()) }).isFailure)
        token = "rotated"
        assertTrue(registerCurrentPushToken({ "a" }, { token }, { sent += it; Result.success(Unit) }).isSuccess)
        assertEquals(listOf("old", "rotated"), sent)
    }

    @Test fun cancellationIsNotTurnedIntoARegistration() = runTest {
        try {
            registerCurrentPushToken({ "a" }, { throw CancellationException("stopped") }, { error("Cancelled") })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
