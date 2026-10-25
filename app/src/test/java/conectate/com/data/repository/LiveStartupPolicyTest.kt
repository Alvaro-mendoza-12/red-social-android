package conectate.com.data.repository

import conectate.com.data.model.LiveCollaborationCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveStartupPolicyTest {
    @Test fun unavailableCapabilitiesNeverSelectAnUnusableLegacyTransport() {
        val offline = java.io.IOException("offline")
        assertSame(offline, runCatching { liveViewerUsesSfu(Result.failure(offline)) }.exceptionOrNull())
        assertTrue(liveViewerUsesSfu(Result.success(LiveCollaborationCapabilities(sfuAvailable = true))))
        assertFalse(liveViewerUsesSfu(Result.success(LiveCollaborationCapabilities(sfuAvailable = false))))
    }

    @Test fun stalledHandshakeFinishesCleanupBeforeOfferingRetry() = runTest {
        var cleaned = false
        val result = awaitLiveConnection<Unit>(100) {
            try { awaitCancellation() } finally { cleaned = true }
        }
        assertTrue(cleaned)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("tardó demasiado"))
    }

    @Test fun navigationCancellationNeverBecomesAConnectionError() = runTest {
        var published = false
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            awaitLiveConnection<Unit>(1_000) { entered.complete(Unit); awaitCancellation() }
            published = true
        }
        entered.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(published)
    }

    @Test fun resultWrappedCancellationStillCancelsTheAttempt() = runTest {
        val cancellation = CancellationException("left screen")
        var reported: Throwable? = null
        try { awaitLiveConnection<Unit> { Result.failure(cancellation) } }
        catch (error: CancellationException) { reported = error }
        // Coroutine stack-trace recovery may copy CancellationException.
        assertTrue(reported is CancellationException)
        assertEquals(cancellation.message, reported?.message)
    }

    @Test fun acceptedHandshakeAndExplicitServerFailureArePreserved() = runTest {
        assertEquals("connected", awaitLiveConnection { Result.success("connected") }.getOrThrow())
        val ended = IllegalStateException("Directo finalizado")
        assertSame(ended, awaitLiveConnection<Unit> { Result.failure(ended) }.exceptionOrNull())
    }
}
