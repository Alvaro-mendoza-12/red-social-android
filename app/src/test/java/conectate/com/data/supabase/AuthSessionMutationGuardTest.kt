package conectate.com.data.supabase

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class AuthSessionMutationGuardTest {
    @Test fun savedCredentialsCannotMixAnAwaitedTokenWithTheNextAccountIdentity() {
        val guard = AuthSessionMutationGuard()
        var currentOwner: String? = "account-a"
        var currentToken: String? = "token-a"
        val awaitedToken = currentToken
        guard.invalidate { currentOwner = "account-b"; currentToken = "token-b" }
        var stored = false
        assertFalse(guard.commitForAccount("account-a", awaitedToken, { currentOwner }, { currentToken }) { stored = true })
        assertFalse(stored)
        assertTrue(guard.commitForAccount("ACCOUNT-B", "token-b", { currentOwner }, { currentToken }) { stored = true })
        assertTrue(stored)
    }

    @Test fun anOlderObserverCannotOverwriteFreshCredentialsForTheSameAccount() {
        val guard = AuthSessionMutationGuard()
        var saved = "fresh"
        assertFalse(guard.commitForAccount("account", "old", { "account" }, { "fresh" }) { saved = "old" })
        assertEquals("fresh", saved)
        assertFalse(guard.commitForAccount("account", "fresh", { null }, { null }) { saved = "logged-out" })
        assertEquals("fresh", saved)
    }

    @Test fun currentRefreshCanPersist() {
        val guard = AuthSessionMutationGuard()
        var session = "old-token"
        assertTrue(guard.commitIfCurrent(guard.snapshot(), { session == "old-token" }) { session = "new-token" })
        assertEquals("new-token", session)
    }

    @Test fun delayedRefreshCannotRestoreLoggedOutAccount() {
        val guard = AuthSessionMutationGuard()
        val request = guard.snapshot()
        var session: String? = "account-a"
        guard.invalidate { session = null }
        assertFalse(guard.commitIfCurrent(request) { session = "account-a-renewed" })
        assertNull(session)
    }

    @Test fun delayedSuccessCannotReplaceTheNewAccount() {
        val guard = AuthSessionMutationGuard()
        val request = guard.snapshot()
        var session = "account-a"
        guard.invalidate { session = "account-b" }
        assertFalse(guard.commitIfCurrent(request) { session = "account-a-renewed" })
        assertEquals("account-b", session)
    }

    @Test fun delayedFailureCannotLogOutTheNewAccount() {
        val guard = AuthSessionMutationGuard()
        val request = guard.snapshot()
        var session: String? = "account-a"
        guard.invalidate { session = "account-b" }
        assertFalse(guard.commitIfCurrent(request) { guard.invalidate { session = null } })
        assertEquals("account-b", session)
    }

    @Test fun tokenReplacementRejectsRefreshEvenWithinTheSameGeneration() {
        val guard = AuthSessionMutationGuard()
        val request = guard.snapshot()
        var token = "replacement-token"
        assertFalse(guard.commitIfCurrent(request, { token == "request-token" }) { token = "stale-token" })
        assertEquals("replacement-token", token)
    }

    @Test fun accountLoginInvalidatesAnOlderStartupReadAtomically() {
        val guard = AuthSessionMutationGuard()
        val startup = guard.snapshot()
        var session: String? = null
        assertTrue(guard.commitIfCurrent(startup) { guard.invalidate { session = "account-b" } })
        assertFalse(guard.commitIfCurrent(startup) { session = null })
        assertEquals("account-b", session)
    }

    @Test fun aFailedCommitDoesNotKeepTheGuardLocked() {
        val guard = AuthSessionMutationGuard()
        try { guard.commitIfCurrent(guard.snapshot()) { error("disk error") }; fail("Expected failure") }
        catch (_: IllegalStateException) { }
        guard.invalidate { }
        assertTrue(guard.commitIfCurrent(guard.snapshot()) { })
    }

    @Test fun importAndThrottleAfterSwitchCannotKeepTheOutgoingSession() {
        val guard = AuthSessionMutationGuard()
        val sdkImportGeneration = guard.snapshot()
        var storedToken = "account-a-token"
        var sdkToken: String? = null
        var refreshSuccessAt = 123L
        guard.invalidate { storedToken = "account-b-token"; refreshSuccessAt = 0L }
        // A suspended import completes late, while the compatibility mutex is held.
        sdkToken = "account-a-token"
        val current = guard.commitIfCurrent(sdkImportGeneration, { storedToken == "account-a-token" }) {
            refreshSuccessAt = 456L
        }
        if (!current) sdkToken = null
        assertFalse(current)
        assertNull(sdkToken)
        assertEquals("account-b-token", storedToken)
        assertEquals(0L, refreshSuccessAt)
        // The new import gets the mutex afterwards and keeps its own token.
        assertTrue(guard.commitIfCurrent(guard.snapshot()) { sdkToken = storedToken })
        assertEquals("account-b-token", sdkToken)
    }

    @Test fun logoutWaitsForFinalCommitAndThenWins() {
        val guard = AuthSessionMutationGuard()
        val request = guard.snapshot()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val logoutStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        var session: String? = "account-a"
        try {
            val save = pool.submit<Boolean> {
                guard.commitIfCurrent(request) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    session = "renewed-account-a"
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val logout = pool.submit {
                logoutStarted.countDown()
                guard.invalidate { session = null }
            }
            assertTrue(logoutStarted.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(save.get(5, TimeUnit.SECONDS))
            logout.get(5, TimeUnit.SECONDS)
            assertNull(session)
            assertFalse(guard.commitIfCurrent(request) { session = "stale-account-a" })
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
