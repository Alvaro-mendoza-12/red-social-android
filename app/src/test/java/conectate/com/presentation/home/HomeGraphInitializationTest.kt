package conectate.com.presentation.home

import dagger.Lazy
import dagger.internal.DoubleCheck
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeGraphInitializationTest {
    @Test fun `repository stays lazy and resolves outside the caller thread`() = runBlocking {
        val caller = Thread.currentThread()
        val calls = AtomicInteger()
        val value = Any()
        var constructionThread: Thread? = null
        val provider = DoubleCheck.lazy(Provider {
            calls.incrementAndGet()
            constructionThread = Thread.currentThread()
            value
        })

        assertEquals(0, calls.get())
        assertSame(value, resolveHomeRepository(provider))
        assertNotSame(caller, constructionThread)
        assertSame(caller, Thread.currentThread())
        assertSame(value, resolveHomeRepository(provider))
        assertEquals(1, calls.get())
    }

    @Test fun `concurrent refreshes share the dagger lazy instance`() = runBlocking {
        val calls = AtomicInteger()
        val value = Any()
        val provider = DoubleCheck.lazy(Provider {
            calls.incrementAndGet()
            value
        })

        val results = List(20) { async { resolveHomeRepository(provider) } }.awaitAll()

        results.forEach { assertSame(value, it) }
        assertEquals(1, calls.get())
    }

    @Test fun `construction failure preserves its type message and original cause`() = runBlocking {
        val failure = IllegalStateException("QA repository construction failure")
        val provider = Lazy<Any> { throw failure }

        val caught = runCatching { resolveHomeRepository(provider) }.exceptionOrNull()

        assertNotNull(caught)
        assertEquals(failure.javaClass, caught!!.javaClass)
        assertEquals(failure.message, caught.message)
        // Coroutine stack-trace recovery may copy an exception when crossing
        // dispatchers. Its original cause must remain available to the caller.
        assertTrue(generateSequence(caught) { it.cause }.take(8).any { it === failure })
    }

    @Test fun `cancelled refresh never begins repository construction`() = runBlocking {
        val calls = AtomicInteger()
        val provider = Lazy { calls.incrementAndGet(); Any() }
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            resolveHomeRepository(provider)
        }

        val failure = runCatching { pending.await() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(0, calls.get())
    }

    @Test fun `cancelled caller cannot publish a completed construction result`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var resultDelivered = false
        val provider = Lazy {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "QA construction gate timed out" }
            Any()
        }
        val pending = async {
            resolveHomeRepository(provider)
            resultDelivered = true
        }

        try {
            // Let async enter withContext before waiting on the independent IO worker.
            kotlinx.coroutines.yield()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            pending.cancel()
            release.countDown()
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
            assertFalse(resultDelivered)
        } finally {
            release.countDown()
            pending.cancel()
        }
    }
}
