package conectate.com.core.network

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class FirstSuccessfulDnsLookupTest {
    private val answer = listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)))

    @Test fun slowPrimaryDoesNotBlockSuccessfulSecondaryAndIsCancelled() {
        val executor = Executors.newFixedThreadPool(2)
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        try {
            assertEquals(answer, firstSuccessfulDnsLookup(executor, listOf(
                { started.countDown(); try { CountDownLatch(1).await(); emptyList() } finally { cancelled.countDown() } },
                { check(started.await(5, TimeUnit.SECONDS)); answer }
            ), 10_000))
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
    }

    @Test fun failingAndEmptyResolversDoNotHideTheValidAnswer() {
        val executor = Executors.newFixedThreadPool(3)
        try {
            assertEquals(answer, firstSuccessfulDnsLookup(executor, listOf(
                { throw java.net.UnknownHostException("isolated failure") }, { emptyList() }, { answer }
            ), 5_000))
        } finally { executor.shutdownNow() }
    }

    @Test fun allUnresponsiveResolversHaveOneSharedDeadline() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val started = System.nanoTime()
            assertTrue(firstSuccessfulDnsLookup(executor, List(2) {
                { CountDownLatch(1).await(); emptyList<InetAddress>() }
            }, 100).isEmpty())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        } finally { executor.shutdownNow() }
    }

    @Test fun noResolversReturnsNoAnswer() {
        val executor = Executors.newSingleThreadExecutor()
        try { assertTrue(firstSuccessfulDnsLookup(executor, emptyList(), 100).isEmpty()) }
        finally { executor.shutdownNow() }
    }
}
