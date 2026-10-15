package conectate.com.presentation.calls

import conectate.com.data.model.CallInvite
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class IncomingCallAnswerControllerTest {
    private val invite = CallInvite("call-local", "Prueba", "caller", "Prueba", contextType = "dm")
    private suspend fun done(c: IncomingCallAnswerController): IncomingAnswerState =
        withTimeout(3_000) { c.state.first { it is IncomingAnswerState.Ready || it is IncomingAnswerState.Failed } }

    @Test fun duplicateAnswerRunsOnePreparation() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>(); val count = AtomicInteger()
        val c = IncomingCallAnswerController(this, { _, _ -> count.incrementAndGet(); entered.complete(Unit); resume.await(); Result.success(Unit) }, { "Seguro" })
        c.answer(invite, null); entered.await(); repeat(10) { c.answer(invite, null) }
        resume.complete(Unit)
        assertEquals(IncomingAnswerState.Ready(invite.callId), done(c)); assertEquals(1, count.get())
    }

    @Test fun dependencyCreationDoesNotBlockAnsweringThread() = runBlocking {
        val thread = Thread.currentThread(); val entered = CompletableDeferred<Thread>(); val resume = CompletableDeferred<Unit>()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val c = IncomingCallAnswerController(this, { _, _ -> entered.complete(Thread.currentThread()); resume.await(); Result.success(Unit) }, { "Seguro" }, dispatcher = io)
            c.answer(invite, null)
            assertTrue(c.state.value is IncomingAnswerState.Preparing)
            assertNotSame(thread, entered.await()); resume.complete(Unit); done(c)
        }
        Unit
    }

    @Test fun recreatingAnObserverKeepsTheSamePendingAnswer() = runBlocking {
        val resume = CompletableDeferred<Unit>(); val count = AtomicInteger()
        val c = IncomingCallAnswerController(this, { _, _ -> count.incrementAndGet(); resume.await(); Result.success(Unit) }, { "Seguro" })
        c.answer(invite, null)
        val oldObserver = launch { c.state.first { it is IncomingAnswerState.Ready } }
        oldObserver.cancelAndJoin()
        c.answer(invite, null); resume.complete(Unit)
        assertTrue(done(c) is IncomingAnswerState.Ready); assertEquals(1, count.get())
    }

    @Test fun failureShowsSafeFeedbackAndCanRetry() = runBlocking {
        val count = AtomicInteger()
        val c = IncomingCallAnswerController(this, { _, _ -> if(count.incrementAndGet()==1) Result.failure(Exception("internal secret")) else Result.success(Unit) }, { "Puedes volver a intentar." })
        c.answer(invite, null); assertEquals("Puedes volver a intentar.", (done(c) as IncomingAnswerState.Failed).message)
        c.answer(invite, null); assertTrue(done(c) is IncomingAnswerState.Ready); assertEquals(2,count.get())
    }

    @Test fun timeoutDoesNotLeaveProcessingForever() = runBlocking {
        val c = IncomingCallAnswerController(this, { _, _ -> delay(5_000); Result.success(Unit) }, { "Seguro" }, timeoutMs = 50)
        c.answer(invite, null); assertTrue(done(c) is IncomingAnswerState.Failed)
    }

    @Test fun scopeCancellationCannotPublishAnAcceptedCall() = runBlocking {
        val job = SupervisorJob(); val scope = CoroutineScope(job + Dispatchers.IO); val entered = CompletableDeferred<Unit>()
        val c = IncomingCallAnswerController(scope, { _, _ -> entered.complete(Unit); awaitCancellation() }, { "Seguro" })
        c.answer(invite,null); entered.await(); job.cancelAndJoin()
        assertFalse(c.state.value is IncomingAnswerState.Ready)
    }

    @Test fun acceptedAnswerCannotBePreparedTwice() = runBlocking {
        val count = AtomicInteger()
        val c = IncomingCallAnswerController(this, { _, _ -> count.incrementAndGet(); Result.success(Unit) }, { "Seguro" })
        c.answer(invite,null); done(c); c.answer(invite,null)
        assertEquals(1,count.get()); assertTrue(c.state.value is IncomingAnswerState.Ready)
    }

    @Test fun unansweredOrFailedInvitationCanSelectAnotherRecipient() {
        assertFalse(isIncomingAnswerInProgress(IncomingAnswerState.Idle,false))
        assertFalse(isIncomingAnswerInProgress(IncomingAnswerState.Failed("call","Seguro"),false))
    }

    @Test fun permissionOrConnectionInProgressPreservesTheChosenAccount() {
        assertTrue(isIncomingAnswerInProgress(IncomingAnswerState.Idle,true))
        assertTrue(isIncomingAnswerInProgress(IncomingAnswerState.Preparing("call"),false))
        assertTrue(isIncomingAnswerInProgress(IncomingAnswerState.Ready("call"),false))
    }

}
