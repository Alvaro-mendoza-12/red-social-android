package conectate.com.core.concurrency

import dagger.Lazy
import dagger.internal.DoubleCheck
import javax.inject.Provider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeferredRepositoryFlowTest {
    @Test fun `provider and flow preparation are deferred and run on IO`() = runBlocking {
        val caller = Thread.currentThread()
        val calls = AtomicInteger()
        var preparationThread: Thread? = null
        val provider = DoubleCheck.lazy(Provider { calls.incrementAndGet(); "own value" })
        val observed = deferredRepositoryFlow(provider) {
            preparationThread = Thread.currentThread()
            flowOf(it)
        }
        assertEquals(0, calls.get())
        assertEquals("own value", observed.first())
        assertNotSame(caller, preparationThread)
        assertSame(caller, Thread.currentThread())
        assertEquals("own value", observed.first())
        assertEquals(1, calls.get())
    }

    @Test fun `cancelled collector does not construct the graph`() = runBlocking {
        val calls = AtomicInteger()
        val observed = deferredRepositoryFlow(Lazy { calls.incrementAndGet() }) { flowOf(it) }
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            observed.first()
        }
        assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
        assertEquals(0, calls.get())
    }
}
