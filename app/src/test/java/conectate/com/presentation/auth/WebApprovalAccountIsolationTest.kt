package conectate.com.presentation.auth

import conectate.com.data.model.StoredAccountSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WebApprovalAccountIsolationTest {
    private val secondary = StoredAccountSession(id = "stored-b", user_id = "b", email = "b@example.test",
        access_token = "access-b", refresh_token = "refresh-b")

    @Test fun `secondary approval uses only its saved credentials without touching the active session`() = runTest {
        val result = resolveWebPairingApprovalSession("stored-b", listOf(secondary), { "a" to "a@example.test" },
            { error("Approval must not prepare or switch the active account") })
        assertEquals("access-b" to "refresh-b", result.getOrThrow())
    }
    @Test fun `removed selection cannot silently approve using another account`() = runTest {
        val result = resolveWebPairingApprovalSession("removed", listOf(secondary), { "a" to "a@example.test" },
            { error("Must not fall back to the active account") })
        assertTrue(result.isFailure)
    }
    @Test fun `active account approval uses refreshed credentials rather than a stale stored token`() = runTest {
        val result = resolveWebPairingApprovalSession("stored-b", listOf(secondary), { "b" to "b@example.test" },
            { "fresh-b" to "fresh-refresh-b" })
        assertEquals("fresh-b" to "fresh-refresh-b", result.getOrThrow())
    }
    @Test fun `account change while preparing credentials rejects the approval`() = runTest {
        var identity: Pair<String?, String?> = "a" to "a@example.test"
        val result = resolveWebPairingApprovalSession(null, emptyList(), { identity }, {
            identity = "b" to "b@example.test"
            "access-b" to "refresh-b"
        })
        assertTrue(result.isFailure)
    }
    @Test fun `empty saved session cannot borrow the active account token`() = runTest {
        val result = resolveWebPairingApprovalSession("stored-b", listOf(secondary.copy(access_token = " ")),
            { "a" to "a@example.test" }, { error("Wrong account") })
        assertTrue(result.isFailure)
    }
    @Test fun `cancelled credential preparation remains cancellation`() = runTest {
        try {
            resolveWebPairingApprovalSession(null, emptyList(), { "a" to "a@example.test" },
                { throw CancellationException("screen closed") })
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
    }
}
