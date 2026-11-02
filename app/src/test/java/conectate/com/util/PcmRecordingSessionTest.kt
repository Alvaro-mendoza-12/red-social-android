package conectate.com.util

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PcmRecordingSessionTest {
    @get:Rule val temp = TemporaryFolder()

    private class Chunk(val bytes: ByteArray = byteArrayOf(), val result: Int = bytes.size, blocked: Boolean = false) {
        val entered = CountDownLatch(1)
        val allowReturn = CountDownLatch(if (blocked) 1 else 0)
    }
    private class Input : PcmRecordingInput {
        override val bufferSize = 8
        val chunks = LinkedBlockingQueue<Chunk>()
        val entered = LinkedBlockingQueue<Int>()
        val reads = AtomicInteger()
        val releases = AtomicInteger()
        val starts = AtomicInteger()
        val concurrent = AtomicInteger()
        val maximumConcurrent = AtomicInteger()
        var startFailure: Exception? = null
        override fun start() { startFailure?.let { throw it }; starts.incrementAndGet() }
        // Simulate vendors whose blocked read only finishes later, after pause/resume.
        override fun stop() = Unit
        override fun read(buffer: ByteArray): Int {
            val count = concurrent.incrementAndGet()
            maximumConcurrent.updateAndGet { maxOf(it, count) }
            val attempt = reads.incrementAndGet()
            entered.offer(attempt)
            try {
                val chunk = chunks.poll(3, TimeUnit.SECONDS) ?: throw IOException("Test input had no chunk")
                chunk.entered.countDown()
                check(chunk.allowReturn.await(3, TimeUnit.SECONDS)) { "Test chunk was not released" }
                chunk.bytes.copyInto(buffer)
                return chunk.result
            } finally { concurrent.decrementAndGet() }
        }
        override fun release() { releases.incrementAndGet() }
        fun waitForRead() { assertNotNull("PCM writer never requested a read", entered.poll(3, TimeUnit.SECONDS)) }
    }
    private fun waitFor(latch: CountDownLatch) = assertTrue(latch.await(3, TimeUnit.SECONDS))
    private fun stop(session: PcmRecordingSession, input: Input) {
        session.requestStop()
        input.chunks.offer(Chunk(result = -3))
        session.awaitClosed()
    }

    @Test fun `rapid pause resume uses one reader and drops a delayed previous segment`() {
        val input = Input()
        val file = temp.newFile()
        val failures = LinkedBlockingQueue<Exception>()
        val session = PcmRecordingSession(file, input, { failures.offer(it) })
        val delayed = Chunk(byteArrayOf(1, 1), blocked = true)
        input.chunks.offer(delayed)
        try {
            session.start()
            waitFor(delayed.entered)
            input.waitForRead()
            repeat(4) { assertTrue(session.pause()); assertTrue(session.resume()) }
            delayed.allowReturn.countDown()
            input.waitForRead()
            val valid = Chunk(byteArrayOf(2, 2))
            input.chunks.offer(valid)
            waitFor(valid.entered)
            input.waitForRead() // The valid chunk has been written before the next read.
            stop(session, input)
            assertArrayEquals(byteArrayOf(2, 2), file.readBytes())
            assertEquals(1, input.maximumConcurrent.get())
            assertEquals(5, input.starts.get())
            assertEquals(1, input.releases.get())
            assertTrue(failures.isEmpty())
        } finally { delayed.allowReturn.countDown(); session.discard(); input.chunks.offer(Chunk(result = -3)) }
    }

    @Test fun `closing the old session never reads or releases the next session input`() {
        val oldInput = Input()
        val newInput = Input()
        val oldFile = temp.newFile()
        val newFile = temp.newFile()
        val old = PcmRecordingSession(oldFile, oldInput, {})
        val next = PcmRecordingSession(newFile, newInput, {})
        val delayed = Chunk(byteArrayOf(1, 1), blocked = true)
        oldInput.chunks.offer(delayed)
        try {
            old.start()
            waitFor(delayed.entered)
            old.requestStop()
            next.start()
            newInput.waitForRead()
            delayed.allowReturn.countDown()
            old.awaitClosed()
            assertTrue(next.isRecording)
            assertEquals(0, newInput.releases.get())
            newInput.chunks.offer(Chunk(byteArrayOf(4, 4)))
            newInput.waitForRead()
            stop(next, newInput)
            assertArrayEquals(byteArrayOf(), oldFile.readBytes())
            assertArrayEquals(byteArrayOf(4, 4), newFile.readBytes())
            assertEquals(1, oldInput.releases.get())
            assertEquals(1, newInput.releases.get())
        } finally {
            delayed.allowReturn.countDown()
            old.discard(); next.discard()
            oldInput.chunks.offer(Chunk(result = -3)); newInput.chunks.offer(Chunk(result = -3))
        }
    }

    @Test fun `active read failure terminates once instead of spinning and releases the microphone`() {
        val input = Input()
        val failure = CountDownLatch(1)
        val session = PcmRecordingSession(temp.newFile(), input, { failure.countDown() })
        input.chunks.offer(Chunk(result = -3))
        try {
            session.start()
            waitFor(failure)
            assertThrows(IOException::class.java) { session.awaitClosed() }
            assertEquals(1, input.reads.get())
            assertEquals(1, input.releases.get())
            assertFalse(session.isRecording)
        } finally { session.discard() }
    }

    @Test fun `discard waits for a blocked writer before deleting its own file`() {
        val input = Input()
        val file = temp.newFile().apply { writeBytes(byteArrayOf(7, 7)) }
        val session = PcmRecordingSession(file, input, {})
        val delayed = Chunk(byteArrayOf(8, 8), blocked = true)
        input.chunks.offer(delayed)
        try {
            session.start()
            waitFor(delayed.entered)
            session.discard()
            assertTrue(file.exists())
            delayed.allowReturn.countDown()
            session.awaitClosed()
            assertFalse(file.exists())
            assertEquals(1, input.releases.get())
        } finally { delayed.allowReturn.countDown(); session.discard() }
    }

    @Test fun `transferred closed PCM survives cleanup of its previous session`() {
        val input = Input()
        val file = temp.newFile()
        val session = PcmRecordingSession(file, input, {})
        try {
            session.start()
            input.waitForRead()
            input.chunks.offer(Chunk(byteArrayOf(9, 9)))
            input.waitForRead()
            stop(session, input)
            assertEquals(file, session.transferFile())
            session.discard()
            session.awaitClosed()
            assertArrayEquals(byteArrayOf(9, 9), file.readBytes())
            assertEquals(1, input.releases.get())
        } finally { session.discard(); input.chunks.offer(Chunk(result = -3)) }
    }

    @Test fun `failed start can still release its private native input exactly once`() {
        val input = Input().apply { startFailure = IOException("microphone unavailable") }
        val session = PcmRecordingSession(temp.newFile(), input, {})
        assertThrows(IOException::class.java) { session.start() }
        session.requestStop()
        session.awaitClosed()
        session.awaitClosed()
        assertEquals(1, input.releases.get())
        session.discard()
    }
}
