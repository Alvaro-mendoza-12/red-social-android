package conectate.com.data.repository.collection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CollectionReadReceiptTest {
    @Test fun strictFailureDoesNotApplyLocalReceipt() = runBlocking {
        var writes = 0
        val result = runCatching { applyCollectionReadReceipt(true, { true }, { false }, { writes++ }) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(0, writes)
    }
    @Test fun confirmedReceiptAppliesOnce() = runBlocking {
        var writes = 0
        applyCollectionReadReceipt(true, { true }, { true }, { writes++ })
        assertEquals(1, writes)
    }
    @Test fun existingLocalFallbackRemainsAvailable() = runBlocking {
        var writes = 0
        applyCollectionReadReceipt(false, { true }, { false }, { writes++ })
        assertEquals(1, writes)
    }
    @Test fun staleInitialSessionDoesNotStartNetworkOperation() = runBlocking {
        var network = 0; var writes = 0
        val result = runCatching { applyCollectionReadReceipt(true, { false }, { network++; true }, { writes++ }) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(0, network); assertEquals(0, writes)
    }
    @Test fun sessionChangedDuringNetworkDoesNotApplyLocalReceipt() = runBlocking {
        var current = true; var writes = 0
        val result = runCatching { applyCollectionReadReceipt(true, { current }, { current = false; true }, { writes++ }) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(0, writes)
    }
    @Test fun cancelledNetworkDoesNotApplyLocalReceipt() = runBlocking {
        var writes = 0
        val result = runCatching { applyCollectionReadReceipt(true, { true }, { throw CancellationException("fixture") }, { writes++ }) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(0, writes)
    }
}
