package conectate.com.data.livekit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LiveCollaborationConnectionGateTest {
    @Test fun aNewAttemptWaitsForCancelledAttemptCleanup() = runTest {
        val gate = LiveCollaborationConnectionGate()
        val entered = CompletableDeferred<Unit>()
        var closed = false
        val first = launch {
            gate.runLatest {
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { delay(500); closed = true } }
            }
        }
        entered.await()
        val second = launch { gate.runLatest { assertTrue(closed); ensureCurrent() } }
        first.join(); second.join()
        assertTrue(first.isCancelled)
        assertFalse(second.isCancelled)
    }

    @Test fun leavingPreventsMediaAfterAnHttpWrapperSwallowsCancellation() = runTest {
        val gate = LiveCollaborationConnectionGate()
        val entered = CompletableDeferred<Unit>()
        var openedMedia = false
        val pending = launch {
            gate.runLatest {
                entered.complete(Unit)
                runCatching { awaitCancellation() }
                ensureCurrent()
                openedMedia = true
            }
        }
        entered.await()
        gate.invalidate()
        pending.join()
        assertTrue(pending.isCancelled)
        assertFalse(openedMedia)
    }

    @Test fun anOldDeferredDisconnectCannotCloseANewConnection() = runTest {
        val gate = LiveCollaborationConnectionGate()
        val oldVersion = gate.invalidate()
        gate.runLatest { ensureCurrent() }
        var closed = false
        gate.ifLatest(oldVersion) { closed = true }
        assertFalse(closed)
        gate.ifLatest(gate.snapshot()) { closed = true }
        assertTrue(closed)
    }
}
