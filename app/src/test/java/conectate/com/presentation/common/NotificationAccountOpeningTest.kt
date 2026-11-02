package conectate.com.presentation.common

import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationAccountOpeningTest {
    private class Session {
        val guard = AuthSessionMutationGuard()
        var account: String? = "a"
        fun capture() = guard.captureAccountLease { account }
        fun enter(id: String?) { guard.invalidate { account = id } }
    }

    @Test fun `rapid taps switch once and retain the notification route until consumed`() = runTest {
        val session = Session()
        val response = CompletableDeferred<Result<AuthSessionLease>>()
        var calls = 0
        val acknowledged = mutableListOf<String>()
        val opening = NotificationAccountOpening(backgroundScope, {
            assertEquals("stored-b", it); calls++; response.await()
        }, acknowledged::add, { "failed" })
        repeat(4) { opening.start("stored-b", "chat/original") }
        assertTrue(opening.isSwitching.value)
        runCurrent()
        assertEquals(1, calls)
        session.enter("b")
        response.complete(Result.success(session.capture()))
        runCurrent()
        assertFalse(opening.isSwitching.value)
        assertEquals(listOf("stored-b"), acknowledged)
        opening.start("other", "chat/incorrect")
        assertEquals("chat/original", opening.takeCompletedRoute())
        assertNull(opening.takeCompletedRoute())
        assertEquals(1, calls)
    }

    @Test fun `an unexpected transport exception releases progress and permits retry`() = runTest {
        val session = Session()
        var failRequest = true
        val opening = NotificationAccountOpening(backgroundScope, {
            if (failRequest) throw IOException("offline")
            session.enter("b"); Result.success(session.capture())
        }, {}, { "Comprueba tu conexión" })
        opening.start("b", "destination")
        runCurrent()
        assertEquals("Comprueba tu conexión", opening.errorMessage.value)
        assertFalse(opening.isSwitching.value)
        failRequest = false
        opening.start("b", "destination")
        assertNull(opening.errorMessage.value)
        runCurrent()
        assertEquals("destination", opening.takeCompletedRoute())
    }

    @Test fun `a hung switch explains timeout and can be retried`() = runTest {
        var failures = 0
        var acknowledgements = 0
        val opening = NotificationAccountOpening(backgroundScope, { awaitCancellation() }, { acknowledgements++ }, {
            assertTrue(it is TimeoutException); failures++; "Inténtalo de nuevo"
        }, timeoutMs = 100)
        opening.start("b", "destination")
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertFalse(opening.isSwitching.value)
        assertEquals(1, failures)
        assertEquals(0, acknowledgements)
        assertNull(opening.takeCompletedRoute())
        opening.start("b", "destination")
        assertTrue(opening.isSwitching.value)
    }

    @Test fun `Result wrapped cancellation remains silent and releases progress`() = runTest {
        var acknowledged = false
        val opening = NotificationAccountOpening(backgroundScope, {
            Result.failure(CancellationException("account changed"))
        }, { acknowledged = true }, { fail("Cancellation must not become a retry error"); "" })
        opening.start("b", "destination")
        runCurrent()
        assertFalse(opening.isSwitching.value)
        assertFalse(acknowledged)
        assertNull(opening.errorMessage.value)
        assertNull(opening.takeCompletedRoute())
    }

    @Test fun `leaving before the switch starts does not leave a loading reservation`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val opening = NotificationAccountOpening(scope, { fail("A left screen must not switch"); error("unexpected") }, {}, { "failed" })
        opening.start("b", "destination")
        scope.cancel()
        runCurrent()
        assertFalse(opening.isSwitching.value)
        assertNull(opening.errorMessage.value)
    }

    @Test fun `leaving an active switch cancels the request without an error or destination`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var cancelled = false
        val opening = NotificationAccountOpening(scope, {
            try { awaitCancellation() } finally { cancelled = true }
        }, {}, { "failed" })
        opening.start("b", "destination")
        runCurrent(); scope.cancel(); runCurrent()
        assertTrue(cancelled)
        assertFalse(opening.isSwitching.value)
        assertNull(opening.errorMessage.value)
        assertNull(opening.takeCompletedRoute())
    }

    @Test fun `returning to the same account cannot revive an earlier switch response`() = runTest {
        val session = Session()
        val response = CompletableDeferred<Result<AuthSessionLease>>()
        var acknowledged = false
        val opening = NotificationAccountOpening(backgroundScope, { response.await() }, { acknowledged = true }, { "failed" })
        opening.start("b", "destination")
        runCurrent()
        session.enter("b")
        val old = session.capture()
        session.enter("c"); session.enter("b")
        response.complete(Result.success(old))
        runCurrent()
        assertFalse(acknowledged)
        assertNull(opening.takeCompletedRoute())
        assertFalse(opening.isSwitching.value)
    }

    @Test fun `an account change before navigation discards the already completed route`() = runTest {
        val session = Session()
        val opening = NotificationAccountOpening(backgroundScope, { Result.success(session.capture()) }, {}, { "failed" })
        opening.start("a", "destination")
        runCurrent()
        assertEquals("destination", opening.completedRoute.value)
        session.enter("b"); session.enter("a")
        assertNull(opening.takeCompletedRoute())
        assertNull(opening.completedRoute.value)
    }

    @Test fun `another session during acknowledgement cannot publish the destination`() = runTest {
        val session = Session()
        val opening = NotificationAccountOpening(backgroundScope, { Result.success(session.capture()) }, {
            session.enter("b")
        }, { "failed" })
        opening.start("a", "destination")
        runCurrent()
        assertNull(opening.takeCompletedRoute())
    }

    @Test fun `a manual login failure stays on the bridge and a later matching login can finish`() = runTest {
        val session = Session()
        val opening = NotificationAccountOpening(backgroundScope, { Result.success(session.capture()) }, {}, { "failed" })
        opening.wrongAccount()
        assertNotNull(opening.errorMessage.value)
        session.enter("b")
        opening.complete(session.capture(), "notification-destination")
        assertNull(opening.errorMessage.value)
        assertEquals("notification-destination", opening.takeCompletedRoute())
        opening.navigationFailed()
        assertNotNull(opening.errorMessage.value)
    }

    @Test fun `empty inputs never start account changes`() = runTest {
        val opening = NotificationAccountOpening(backgroundScope, { fail("Must not start"); error("unexpected") }, {}, { "failed" })
        opening.start(" ", "destination")
        opening.start("b", " ")
        runCurrent()
        assertFalse(opening.isSwitching.value)
    }

    @Test fun `a manual login acknowledgement failure offers retry without crashing navigation`() = runTest {
        val session = Session()
        val opening = NotificationAccountOpening(backgroundScope, { Result.success(session.capture()) }, {}, { "Inténtalo de nuevo" })
        opening.complete(session.capture(), "destination") { throw IOException("local preferences unavailable") }
        assertEquals("Inténtalo de nuevo", opening.errorMessage.value)
        assertNull(opening.takeCompletedRoute())
        opening.complete(session.capture(), "destination")
        assertEquals("destination", opening.takeCompletedRoute())
    }

    @Test fun `a manual login must match both the successful login and the canonical recipient`() {
        assertTrue(matchesNotificationSignIn(" B ", "b", "updated@example.test", "b", "old@example.test"))
        assertTrue(matchesNotificationSignIn("b", "b", " B@EXAMPLE.TEST ", null, "b@example.test"))
        assertFalse(matchesNotificationSignIn("a", "b", "b@example.test", "b", "b@example.test"))
        assertFalse(matchesNotificationSignIn("b", "b", "same@example.test", "other", "same@example.test"))
        assertFalse(matchesNotificationSignIn("b", "b", "b@example.test", null, null))
        assertFalse(matchesNotificationSignIn("b", null, "b@example.test", null, "b@example.test"))
    }

    @Test fun `a recipient using the server id can open the same imported profile session`() {
        assertTrue(matchesNotificationSignIn("profile-id", "profile-id", "owner@example.test", "ubuntu-id", null,
            setOf("profile-id", "ubuntu-id")))
        assertFalse(matchesNotificationSignIn("profile-id", "profile-id", "owner@example.test", "foreign-id", "owner@example.test",
            setOf("profile-id", "ubuntu-id")))
        assertFalse(matchesNotificationSignIn("previous-login", "profile-id", "owner@example.test", "ubuntu-id", null,
            setOf("profile-id", "ubuntu-id")))
    }
}
