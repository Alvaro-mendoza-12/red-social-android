package conectate.com.data.webrtc

import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class MediaTaskExecutorTest {
    @Test fun aLateNativeCallbackCannotThrowAfterTeardown() {
        val delegate = Executors.newSingleThreadExecutor()
        val queue = MediaTaskExecutor(delegate) { fail("No native task should run") }
        queue.shutdown()
        var unavailable = false
        queue.execute(onUnavailable = { unavailable = true }) { fail("Disposed engine was accessed") }
        assertTrue(unavailable)
    }
    @Test fun aNativeJavaFailureDoesNotPreventLaterCleanup() {
        val delegate = Executors.newSingleThreadExecutor()
        val errors = mutableListOf<Throwable>()
        val completed = CountDownLatch(1)
        val queue = MediaTaskExecutor(delegate) { errors.add(it) }
        try {
            queue.execute { throw IllegalStateException("MediaStreamTrack has been disposed") }
            queue.execute { completed.countDown() }
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
            assertTrue(errors.single() is IllegalStateException)
        } finally { queue.shutdown() }
    }
    @Test fun queuedWorkReportsUnavailableWhenAnEarlierTaskClosesTheEngine() {
        val delegate = Executors.newSingleThreadExecutor()
        val held = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val queue = MediaTaskExecutor(delegate) { fail(it.message) }
        queue.execute { held.await(3, TimeUnit.SECONDS); queue.shutdown() }
        queue.execute(onUnavailable = { completed.countDown() }) { fail("Access after shutdown") }
        held.countDown()
        assertTrue(completed.await(3, TimeUnit.SECONDS))
    }
}
