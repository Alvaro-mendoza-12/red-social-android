package conectate.com.presentation.wallet

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayQueueVisitTest {
    private fun clear(queue: PlayPurchaseQueue) { queue.javaClass.methods.firstOrNull { it.name == "cancelPending" }?.invoke(queue) }
    @Test fun changingVisitBeforeDispatchCannotRunTheOldVerification() = runTest {
        val busy = mutableListOf<Boolean>(); var calls = 0
        val queue = PlayPurchaseQueue(backgroundScope,busy::add)
        queue.submit("a","token") { calls++ }; clear(queue); runCurrent()
        assertEquals(0,calls); assertEquals(false,busy.last())
    }
    @Test fun aNewVisitIsNotBlockedByThePreviousVisitsNonCancellableReply() = runTest {
        val busy = mutableListOf<Boolean>(); val old = CompletableDeferred<Unit>(); val current = CompletableDeferred<Unit>()
        val queue = PlayPurchaseQueue(backgroundScope,busy::add); var newStarted = false
        queue.submit("a","token") { withContext(NonCancellable) { old.await() } }; runCurrent()
        try {
            clear(queue)
            queue.submit("a","token") { newStarted = true; current.await() }; runCurrent()
            assertTrue(newStarted); assertEquals(true,busy.last())
            old.complete(Unit); runCurrent(); assertEquals(true,busy.last())
            current.complete(Unit); runCurrent(); assertEquals(false,busy.last())
        } finally { old.complete(Unit); current.complete(Unit) }
    }
    @Test fun clearingCancelsEveryQueuedTokenAndReleasesBusy() = runTest {
        var sent = 0; val busy = mutableListOf<Boolean>()
        val queue = PlayPurchaseQueue(backgroundScope,busy::add)
        queue.submit("a","first") { sent++ }; queue.submit("a","second") { sent++ }
        clear(queue); runCurrent(); assertEquals(0,sent); assertEquals(false,busy.last())
    }
    @Test fun duplicateCallbacksRemainDeduplicatedWithinTheSameVisit() = runTest {
        var calls = 0; val response = CompletableDeferred<Unit>()
        val queue = PlayPurchaseQueue(backgroundScope) { }
        queue.submit("a","token") { calls++; response.await() }; queue.submit("a","token") { calls++; response.await() }
        runCurrent(); assertEquals(1,calls); response.complete(Unit); runCurrent()
    }
}
