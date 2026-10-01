package conectate.com.data.livekit

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class LatestFrameDispatcherTest {
    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    @Test fun aBurstKeepsOnlyNewestPendingFrameAndSchedulesOnce() {
        val worker = Worker(); val processed = mutableListOf<Int>(); val released = mutableListOf<Int>()
        val queue = LatestFrameDispatcher(worker, processed::add, released::add) { throw AssertionError(it) }
        repeat(100) { queue.submit(it) }
        assertEquals(1, worker.tasks.size); assertEquals((0..98).toList(), released)
        worker.drain(); assertEquals(listOf(99), processed); assertEquals((0..99).toList(), released)
    }
    @Test fun nextFrameCanArriveWhileProcessingWithoutAnotherDrainTask() {
        val worker = Worker(); val processed = mutableListOf<Int>(); val released = mutableListOf<Int>()
        lateinit var queue: LatestFrameDispatcher<Int>
        queue = LatestFrameDispatcher(worker, { processed.add(it); if (it == 1) queue.submit(2) }, released::add) { throw AssertionError(it) }
        queue.submit(1); worker.drain()
        assertEquals(listOf(1, 2), processed); assertEquals(listOf(1, 2), released)
        assertTrue(worker.tasks.isEmpty())
    }
    @Test fun clearingReleasesPendingFrameWithoutProcessing() {
        val worker = Worker(); val processed = mutableListOf<Int>(); val released = mutableListOf<Int>()
        val queue = LatestFrameDispatcher(worker, processed::add, released::add) { throw AssertionError(it) }
        queue.submit(1); queue.clearPending(); worker.drain()
        assertTrue(processed.isEmpty()); assertEquals(listOf(1), released)
        queue.submit(2); worker.drain(); assertEquals(listOf(2), processed)
    }
    @Test fun closedQueueReleasesPendingAndFutureFramesExactlyOnce() {
        val worker = Worker(); val processed = mutableListOf<Int>(); val released = mutableListOf<Int>()
        val queue = LatestFrameDispatcher(worker, processed::add, released::add) { throw AssertionError(it) }
        queue.submit(1); queue.close(); queue.close(); queue.submit(2); worker.drain()
        assertTrue(processed.isEmpty()); assertEquals(listOf(1, 2), released)
    }
    @Test fun closeDuringProcessingDropsPendingButReleasesActiveFrame() {
        val worker = Worker(); val processed = mutableListOf<Int>(); val released = mutableListOf<Int>()
        lateinit var queue: LatestFrameDispatcher<Int>
        queue = LatestFrameDispatcher(worker, { processed.add(it); queue.submit(2); queue.close() }, released::add) { throw AssertionError(it) }
        queue.submit(1); worker.drain()
        assertEquals(listOf(1), processed); assertEquals(listOf(2, 1), released)
    }
    @Test fun rejectedWorkerReleasesFrameAndClosesQueue() {
        val released = mutableListOf<Int>(); val failures = mutableListOf<Throwable>()
        val queue = LatestFrameDispatcher(Executor { throw RejectedExecutionException() }, { _: Int -> fail() }, released::add, failures::add)
        queue.submit(1); queue.submit(2)
        assertEquals(listOf(1, 2), released); assertEquals(1, failures.size)
    }
    @Test fun processingFailureDoesNotLeakOrStrandNextFrame() {
        val worker = Worker(); val released = mutableListOf<Int>(); val failures = mutableListOf<Throwable>()
        lateinit var queue: LatestFrameDispatcher<Int>
        queue = LatestFrameDispatcher(worker, { if (it == 1) { queue.submit(2); error("synthetic") } }, released::add, failures::add)
        queue.submit(1); worker.drain()
        assertEquals(listOf(1, 2), released); assertEquals(1, failures.size)
    }
    @Test fun concurrentBurstAndShutdownReleaseEveryOwnedFrameOnce() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val started = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val queue = LatestFrameDispatcher(executor, { _: Int -> started.countDown(); assertTrue(resume.await(5, java.util.concurrent.TimeUnit.SECONDS)) },
            { frame -> released.computeIfAbsent(frame) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet() }, failures::add)
        try {
            queue.submit(0); assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val producers = (0..3).map { producer -> Thread { repeat(100) { queue.submit(1 + producer * 100 + it) } }.apply { start() } }
            producers.forEach { it.join(5_000); assertFalse(it.isAlive) }
            queue.close(); resume.countDown(); executor.shutdown()
            assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(failures.isEmpty()); assertEquals(401, released.size)
            assertTrue(released.values.all { it.get() == 1 })
        } finally {
            queue.close(); resume.countDown(); executor.shutdownNow()
        }
    }
}
