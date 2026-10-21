package conectate.com.core.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class LocalAccountSnapshotTest {
    @Test fun `cache IO runs outside the caller thread`() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "local-cache-reader") }.asCoroutineDispatcher().use { io ->
            val caller = Thread.currentThread()
            val result = readLocalAccountSnapshot({ "alice" }, io) { owner ->
                assertNotSame(caller, Thread.currentThread())
                assertEquals("alice", owner)
                listOf("saved")
            }
            assertEquals(listOf("saved"), result)
        }
    }

    @Test fun `account changes discard a pending snapshot`() = runBlocking {
        var owner: String? = "alice"
        assertNull(readLocalAccountSnapshot({ owner }) { captured ->
            assertEquals("alice", captured)
            owner = "bob"
            listOf("alice-private")
        })
    }

    @Test fun `logout discards a pending snapshot`() = runBlocking {
        var owner: String? = "alice"
        assertNull(readLocalAccountSnapshot({ owner }) { owner = null; "private" })
    }

    @Test fun `cancellation is not converted to a cached success`() = runBlocking {
        try {
            readLocalAccountSnapshot({ "alice" }) { throw CancellationException("cancelled") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
