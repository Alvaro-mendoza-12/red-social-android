package conectate.com.data.auth

import conectate.com.data.localfallback.AccountDeletedEvents
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionNoticeTest {
    private class Session {
        val guard = AuthSessionMutationGuard()
        var account: String? = "a"
        var token: String? = "credential-a"
        var deletions = 0
        fun enter(id: String, credential: String) = guard.invalidate { account = id; token = credential }
        fun accept(notice: AccountDeletionNotice) = notice.commitIfCurrent(guard, { account }, { token }) {
            deletions++
            guard.invalidate { account = null; token = null }
        }
    }

    @Test fun `a deleted account response for an old credential cannot close the current account`() {
        val session = Session()
        val notice = AccountDeletionNotice.fromAuthorization("Bearer credential-a")!!
        session.enter("b", "credential-b")
        assertFalse(session.accept(notice))
        assertEquals("b", session.account)
        assertEquals(0, session.deletions)
    }

    @Test fun `returning to the same identity with new credentials does not revive an HTTP response`() {
        val session = Session()
        val notice = AccountDeletionNotice.fromAuthorization("Bearer credential-a")!!
        session.enter("b", "credential-b")
        session.enter("a", "renewed-credential-a")
        assertFalse(session.accept(notice))
        assertEquals("a", session.account)
        assertEquals(0, session.deletions)
    }

    @Test fun `a current authenticated deletion closes once and cannot close a later account`() {
        val session = Session()
        val notice = AccountDeletionNotice.fromAuthorization(" bearer   credential-a ")!!
        assertTrue(session.accept(notice))
        assertFalse(session.accept(notice))
        session.enter("b", "credential-b")
        assertFalse(session.accept(notice))
        assertEquals(1, session.deletions)
        assertEquals("b", session.account)
    }

    @Test fun `realtime deletion retains its generation even when returning with the same credential`() {
        val session = Session()
        val notice = AccountDeletionNotice.fromSession(session.guard.captureAccountLease { session.account })
        session.enter("b", "credential-b")
        session.enter("a", "credential-a")
        assertFalse(session.accept(notice))
        assertEquals(0, session.deletions)
    }

    @Test fun `public login and malformed authorization cannot emit a global logout signal`() = runTest {
        val received = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { AccountDeletedEvents.events.first() }
        for (header in listOf(null, "", " ", "Basic credential-a", "Bearer", "Bearer ", "Bearer a b")) {
            assertNull(AccountDeletionNotice.fromAuthorization(header))
            assertFalse(AccountDeletedEvents.notifyAccountDeletedFromAuthorization(header))
        }
        runCurrent()
        assertFalse(received.isCompleted)
        assertTrue(AccountDeletedEvents.notifyAccountDeletedFromAuthorization("Bearer credential-a"))
        runCurrent()
        assertTrue(Session().accept(received.await()))
    }

    @Test fun `an already invalid realtime session cannot enqueue its deletion`() = runTest {
        val session = Session()
        val lease = session.guard.captureAccountLease { session.account }
        val received = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { AccountDeletedEvents.events.first() }
        session.enter("b", "credential-b")
        AccountDeletedEvents.notifyAccountDeleted(lease)
        runCurrent()
        assertFalse(received.isCompleted)
        AccountDeletedEvents.notifyAccountDeleted(session.guard.captureAccountLease { session.account })
        runCurrent()
        assertTrue(session.accept(received.await()))
    }

    @Test fun `no account or no credential can be signed out by an HTTP signal`() {
        val notice = AccountDeletionNotice.fromAuthorization("Bearer credential-a")!!
        val session = Session().apply { account = null }
        assertFalse(session.accept(notice))
        session.account = "a"; session.token = null
        assertFalse(session.accept(notice))
    }

    @Test fun `a new login cannot enter between the owner check and the logout commit`() {
        val session = Session()
        val notice = AccountDeletionNotice.fromAuthorization("Bearer credential-a")!!
        val deletionEntered = CountDownLatch(1)
        val releaseDeletion = CountDownLatch(1)
        val loginAttempted = CountDownLatch(1)
        val loginCommitted = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val deletion = workers.submit<Boolean> {
                notice.commitIfCurrent(session.guard, { session.account }, { session.token }) {
                    deletionEntered.countDown()
                    check(releaseDeletion.await(5, TimeUnit.SECONDS))
                    session.guard.invalidate { session.account = null; session.token = null }
                }
            }
            assertTrue(deletionEntered.await(5, TimeUnit.SECONDS))
            val login = workers.submit {
                loginAttempted.countDown()
                session.enter("b", "credential-b")
                loginCommitted.countDown()
            }
            assertTrue(loginAttempted.await(5, TimeUnit.SECONDS))
            assertFalse("Login must wait until the captured logout finishes", loginCommitted.await(100, TimeUnit.MILLISECONDS))
            releaseDeletion.countDown()
            assertTrue(deletion.get(5, TimeUnit.SECONDS))
            login.get(5, TimeUnit.SECONDS)
            assertEquals("b", session.account)
            assertEquals("credential-b", session.token)
        } finally {
            releaseDeletion.countDown()
            workers.shutdownNow()
            workers.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
