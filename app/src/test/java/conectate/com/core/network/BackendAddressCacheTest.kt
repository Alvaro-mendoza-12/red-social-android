package conectate.com.core.network

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class BackendAddressCacheTest {
    private var now = 0L
    private val answer = listOf(InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)))
    private val cache = BackendAddressCache("backend.example", { now })

    @Test fun concurrentOfflineRequestsPerformOneLookupInsteadOfSerialTimeouts() {
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val requests = List(8) {
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    try {
                        cache.lookup { calls.incrementAndGet(); emptyList() }
                        false
                    } catch (_: UnknownHostException) { true }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            requests.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally { start.countDown(); executor.shutdownNow() }
    }

    @Test fun connectivityRecoveryIsRetriedAfterTheShortCooldown() {
        assertThrows(UnknownHostException::class.java) { cache.lookup { emptyList() } }
        now = 4_999
        assertThrows(UnknownHostException::class.java) { cache.lookup { fail("Too soon"); answer } }
        now = 5_000
        assertEquals(answer, cache.lookup { answer })
        assertEquals(answer, cache.lookup { fail("Fresh answer must be reused"); emptyList() })
    }

    @Test fun successfulAnswersAreRefreshedAfterTheirLease() {
        assertEquals(answer, cache.lookup { answer })
        now = 5 * 60_000L
        val changed = listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)))
        assertEquals(changed, cache.lookup { changed })
    }

    @Test fun failedRefreshReusesStaleAnswerWithoutRepeatingTheLookup() {
        cache.lookup { answer }
        now = 6 * 60_000L
        assertEquals(answer, cache.lookup { emptyList() })
        now++
        assertEquals(answer, cache.lookup { fail("Failures must also be coalesced"); emptyList() })
    }

    @Test fun failedRefreshesCannotKeepStaleAddressesAliveIndefinitely() {
        cache.lookup { answer }
        now = 20 * 60_000L - 1
        assertEquals(answer, cache.lookup { emptyList() })
        now++
        assertThrows(UnknownHostException::class.java) {
            cache.lookup { fail("Cooldown still applies"); emptyList() }
        }
    }

    @Test fun interruptedResolutionDoesNotPoisonTheNextAttempt() {
        assertThrows(InterruptedException::class.java) { cache.lookup { throw InterruptedException() } }
        assertEquals(answer, cache.lookup { answer })
    }
}
