package conectate.com.data.repository

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GiftSendCoordinatorTest {
    private val disk = mutableMapOf<String, String>()
    private fun coordinator() = GiftSendCoordinator(disk::get) { operation, key ->
        if (key == null) disk.remove(operation) else disk[operation] = key
    }

    @Test
    fun `lost commit response then process recreation retries the original charge`() = runTest {
        val ledger = mutableMapOf<String, String>()
        var charges = 0
        fun server(key: String): String = ledger.getOrPut(key) { charges++; "receipt-$charges" }
        val first = coordinator().execute("operation", "first-key") { key ->
            assertEquals("first-key", disk["operation"])
            server(key)
            throw IOException("response lost after commit")
        }
        assertTrue(first.isFailure)
        val retried = coordinator().execute("operation", "new-ui-key") { key -> server(key) }
        assertEquals("receipt-1", retried.getOrThrow())
        assertEquals(1, charges)
        assertFalse(coordinator().hasPending("operation"))

        assertEquals("receipt-2", coordinator().execute("operation", "next-confirmed-purchase") { server(it) }.getOrThrow())
        assertEquals(2, charges)
    }

    @Test
    fun `a second tap cannot launch a concurrent gift`() = runTest {
        val coordinator = coordinator()
        val response = CompletableDeferred<String>()
        val first = async { coordinator.execute("operation", "first") { response.await() } }
        runCurrent()
        var secondSent = false
        val second = coordinator.execute("other", "second") { secondSent = true; "duplicate" }
        assertTrue(second.isFailure)
        assertFalse(secondSent)
        response.complete("receipt")
        assertEquals("receipt", first.await().getOrThrow())
    }

    @Test
    fun `cancelled request preserves identity and releases the send gate`() = runTest {
        val coordinator = coordinator()
        val first = async { coordinator.execute("operation", "original") { CompletableDeferred<String>().await() } }
        runCurrent()
        first.cancel()
        runCurrent()
        assertTrue(coordinator.hasPending("operation"))
        assertEquals("original", coordinator.execute("operation", "replacement") { it }.getOrThrow())
    }

    @Test
    fun `disk write failure prevents the request leaving the device`() = runTest {
        var sent = false
        val coordinator = GiftSendCoordinator({ null }, { _, _ -> throw IOException("disk full") })
        assertTrue(coordinator.execute("operation", "key") { sent = true }.isFailure)
        assertFalse(sent)
    }

    @Test
    fun `pending gifts are isolated by account recipient live gift and visibility`() {
        val base = giftOperationKey("sender", "host", "live", "gift", true)
        assertEquals(base, giftOperationKey(" sender ", "host", "live", "gift", true))
        listOf(
            giftOperationKey("other", "host", "live", "gift", true),
            giftOperationKey("sender", "opponent", "live", "gift", true),
            giftOperationKey("sender", "host", "next-live", "gift", true),
            giftOperationKey("sender", "host", "live", "other-gift", true),
            giftOperationKey("sender", "host", "live", "gift", false),
        ).forEach { assertNotEquals(base, it) }
    }
}
