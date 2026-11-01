package conectate.com.presentation.messages

import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareRequestSessionTest {
    private class Account {
        var id: String? = "a"
        val auth = AuthSessionMutationGuard()
        fun lease() = auth.captureAccountLease { id }
    }
    private val destination = ShareDestination("chat", ShareDestinationType.CHAT, "Chat")

    @Test fun recreatingTheSameUiDoesNotCancelOrRestartDelivery() = runTest {
        val account = Account(); val session = ShareRequestSession(this, account::lease)
        val release = CompletableDeferred<Unit>(); var calls = 0
        assertTrue(session.bind("a"))
        session.start("a", 1) { calls++; release.await(); ShareActionState.Completed("done", false) }
        runCurrent(); assertFalse(session.bind("a")); assertTrue(session.state.value is ShareActionState.Running)
        release.complete(Unit); runCurrent()
        assertEquals(1, calls); assertEquals("a", session.state.value.requestId)
        assertTrue(session.state.value is ShareActionState.Completed)
    }

    @Test fun replacingTheRequestRejectsAnUncancellableOldCompletion() = runTest {
        val account = Account(); val session = ShareRequestSession(this, account::lease); val release = CompletableDeferred<Unit>()
        session.bind("a"); session.start("a", 1) { withContext(NonCancellable) { release.await() }; ShareActionState.Completed("old", false) }
        runCurrent(); session.bind("b"); release.complete(Unit); runCurrent()
        assertSame(ShareActionState.Idle, session.state.value)
        assertFalse(session.close("a"))
        assertTrue(session.start("b", 1) { ShareActionState.Completed("new", false) })
        runCurrent(); assertEquals("b", session.state.value.requestId)
    }

    @Test fun aRetryInvalidatesCallbacksFromThePreviousAttemptOfTheSameRequest() = runTest {
        val account = Account(); val session = ShareRequestSession(this, account::lease)
        lateinit var old: ShareRequestSession.Ticket; val release = CompletableDeferred<Unit>()
        session.bind("r"); session.start("r", 1) { old = it; ShareActionState.Failed("offline", true) }; runCurrent()
        session.start("r", 1) { release.await(); ShareActionState.Completed("done", false) }; runCurrent()
        assertTrue(runCatching { session.report(old, ShareActionState.Completed("late", false)) }.exceptionOrNull() is CancellationException)
        assertTrue(session.state.value is ShareActionState.Running)
        release.complete(Unit); runCurrent()
    }

    @Test fun oldCompletionCannotBeRenderedAsTheNextRequestsCompletion() {
        assertSame(ShareActionState.Idle, ShareActionState.Completed("old", false, requestId = "a").forRequest("b"))
        assertSame(ShareActionState.Idle, ShareActionState.Failed("old", requestId = "a").forRequest("b"))
    }

    @Test fun signingInAgainAsTheSameAccountInvalidatesTheOldPendingSend() = runTest {
        val account = Account(); val session = ShareRequestSession(this, account::lease); var sent = false
        session.bind("r"); session.start("r", 1) { sent = true; ShareActionState.Completed("wrong", false) }
        account.auth.invalidate {}; runCurrent()
        assertFalse(sent); assertTrue(session.state.value is ShareActionState.Failed)
        assertFalse(session.start("r", 1) { ShareActionState.Completed("wrong", false) })
    }

    @Test fun switchingAccountsDuringAReadStopsTheSendAndLateUiResult() = runTest {
        val account = Account(); val session = ShareRequestSession(this, account::lease)
        val release = CompletableDeferred<Unit>(); var sent = false
        session.bind("r"); session.start("r", 1) { ticket ->
            release.await(); session.requireCurrent(ticket); sent = true; ShareActionState.Completed("wrong", false)
        }
        runCurrent(); account.auth.invalidate { account.id = "b" }; release.complete(Unit); runCurrent()
        assertFalse(sent); assertTrue(session.state.value is ShareActionState.Failed)
    }

    @Test fun ordinaryTokenRenewalDoesNotInvalidateTheRequest() = runTest {
        val account = Account(); val session = ShareRequestSession(this, account::lease)
        session.bind("r"); account.auth.commitIfCurrent(account.auth.snapshot()) {}
        assertTrue(session.start("r", 1) { ShareActionState.Completed("done", false) }); runCurrent()
        assertTrue(session.state.value is ShareActionState.Completed)
    }

    @Test fun aDoubleTapCannotLaunchTwoBatchesBeforeTheFirstCoroutineRuns() = runTest {
        val account = Account(); val session = ShareRequestSession(this, account::lease); var sends = 0
        session.bind("r")
        assertTrue(session.start("r", 1) { sends++; ShareActionState.Completed("done", false) })
        assertFalse(session.start("r", 1) { sends++; ShareActionState.Completed("wrong", false) })
        runCurrent(); assertEquals(1, sends)
    }

    @Test fun queuedNavigationIsStateForTheCurrentUiAndClosingCancelsOnlyItsForegroundTask() = runTest {
        val account = Account(); val session = ShareRequestSession(this, account::lease)
        var durable = false; var cancelled = false
        session.bind("r"); session.start("r", 1) { ticket ->
            try {
                durable = true
                session.report(ticket, ShareActionState.Queued(destination, ticket.id))
                awaitCancellation()
            } finally { cancelled = true }
        }
        runCurrent(); assertFalse(session.bind("r"))
        assertEquals(destination, (session.state.value as ShareActionState.Queued).destination)
        assertTrue(session.close("r")); runCurrent()
        assertTrue(durable); assertTrue(cancelled); assertSame(ShareActionState.Idle, session.state.value)
    }

    @Test fun unexpectedFailuresAreReportedWithoutDisplayingTechnicalDetails() = runTest {
        val account = Account(); val errors = mutableListOf<Throwable>()
        val session = ShareRequestSession(this, account::lease, errors::add)
        session.bind("r"); session.start("r", 1) { throw java.io.IOException("internal endpoint secret") }; runCurrent()
        val failed = session.state.value as ShareActionState.Failed
        assertTrue(failed.retryPending); assertFalse(failed.message.contains("secret")); assertEquals(1, errors.size)
    }

    @Test fun aCancellationFromTheTransportDoesNotLeaveAnEndlessSpinner() = runTest {
        val account = Account(); val session = ShareRequestSession(this, account::lease)
        session.bind("r"); session.start("r", 1) { throw CancellationException("transport") }; runCurrent()
        assertTrue(session.state.value is ShareActionState.Failed)
        assertTrue(session.start("r", 1) { ShareActionState.Completed("retry", false) }); runCurrent()
        assertTrue(session.state.value is ShareActionState.Completed)
    }

    @Test fun anOldActionCannotRebindTheNewRequest() = runTest {
        val account = Account(); val session = ShareRequestSession(this, account::lease)
        session.bind("a"); session.bind("b")
        assertFalse(session.start("a", 1) { ShareActionState.Completed("wrong", false) })
        assertNotNull(session.accountForRequest("b")); assertNull(session.accountForRequest("a"))
    }

    @Test fun anOldQueuedResultCannotCloseANewArrivalWithTheSamePayloadId() = runTest {
        val account = Account(); val session = ShareRequestSession(this, account::lease)
        val gate = IncomingShareDeliveryGate<String>()
        val first = gate.publish(gate.beginIntent(), "same payload")!!
        session.bind(first.requestId)
        session.start(first.requestId, 1) { ShareActionState.Queued(destination, first.requestId) }
        runCurrent()
        val oldResult = session.state.value
        val next = gate.publish(gate.beginIntent(), "same payload")!!
        assertSame(ShareActionState.Idle, oldResult.forRequest(next.requestId))
        session.bind(next.requestId)
        assertFalse(session.close(first.requestId))
        assertSame(next, gate.consume(next)?.delivery)
    }
}
