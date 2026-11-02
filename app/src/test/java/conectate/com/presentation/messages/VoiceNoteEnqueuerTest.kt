package conectate.com.presentation.messages

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceNoteEnqueuerTest {
    @get:Rule val temp = TemporaryFolder()
    private inner class Fixture(scope: TestScope) {
        var current = true
        var busy = false
        var recovered = 0
        var queued = 0
        val errors = mutableListOf<String>()
        val file = temp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val enqueuer = VoiceNoteEnqueuer(scope.backgroundScope, { busy = it }, { errors += it },
            StandardTestDispatcher(scope.testScheduler))
        fun enqueue(send: suspend (suspend () -> Unit) -> Result<Unit>) =
            enqueuer.enqueue(file, { current }, send, { queued++ }, { recovered++ })
    }

    @Test fun `repeated send taps reserve only one local queue operation`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        repeat(5) { f.enqueue { queued -> calls++; gate.await(); queued(); Result.success(Unit) } }
        assertTrue(f.busy)
        runCurrent()
        assertEquals(1, calls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, f.queued)
        assertFalse(f.busy)
    }

    @Test fun `private source is kept until its durable copy and queue acknowledgement exist`() = runTest {
        val f = Fixture(this)
        val copy = temp.newFile()
        val gate = CompletableDeferred<Unit>()
        f.enqueue { queued ->
            gate.await()
            copy.writeBytes(f.file.readBytes())
            queued()
            Result.success(Unit)
        }
        runCurrent()
        assertTrue(f.file.exists())
        gate.complete(Unit)
        runCurrent()
        assertFalse(f.file.exists())
        assertArrayEquals(byteArrayOf(1, 2, 3), copy.readBytes())
        assertEquals(1, f.queued)
    }

    @Test fun `failed local acceptance preserves the recording and restores retry`() = runTest {
        val f = Fixture(this)
        f.enqueue { Result.failure(IOException("disk full")) }
        runCurrent()
        assertTrue(f.file.exists())
        assertEquals(1, f.recovered)
        assertEquals(0, f.queued)
        assertEquals(1, f.errors.size)
        assertFalse(f.busy)
    }

    @Test fun `failed network delivery after local acceptance does not create another review draft`() = runTest {
        val f = Fixture(this)
        f.enqueue { queued -> queued(); Result.failure(IOException("offline")) }
        runCurrent()
        assertFalse(f.file.exists())
        assertEquals(1, f.queued)
        assertEquals(0, f.recovered)
        assertTrue(f.errors.single().contains("pendiente"))
    }

    @Test fun `a replaced owner does not send or display an old failure`() = runTest {
        val f = Fixture(this)
        var sends = 0
        f.enqueue { sends++; Result.failure(IOException("obsolete")) }
        f.current = false
        runCurrent()
        assertEquals(0, sends)
        assertTrue(f.errors.isEmpty())
        assertFalse(f.busy)
    }

    @Test fun `late acknowledgement can clean its private source without clearing another owners UI`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.enqueue { queued -> gate.await(); queued(); Result.success(Unit) }
        runCurrent()
        f.current = false
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, f.queued)
        assertFalse(f.file.exists())
        assertFalse(f.busy)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun `cancellation is not reported as an ordinary failed send`() = runTest {
        val f = Fixture(this)
        f.enqueue { throw CancellationException("account switched") }
        runCurrent()
        assertEquals(0, f.recovered)
        assertTrue(f.errors.isEmpty())
        assertFalse(f.busy)
    }

    @Test fun `the next note can queue while old network delivery is still pending`() = runTest {
        val f = Fixture(this)
        val firstNetwork = CompletableDeferred<Unit>()
        val secondDisk = CompletableDeferred<Unit>()
        f.enqueue { queued -> queued(); firstNetwork.await(); Result.success(Unit) }
        runCurrent()
        assertFalse(f.busy)
        val secondFile = temp.newFile()
        var secondQueued = false
        f.enqueuer.enqueue(secondFile, { true }, { queued -> secondDisk.await(); queued(); Result.success(Unit) },
            { secondQueued = true }, {})
        runCurrent()
        assertTrue(f.busy)
        firstNetwork.complete(Unit)
        runCurrent()
        assertTrue(f.busy) // The old finally cannot release the new reservation.
        secondDisk.complete(Unit)
        runCurrent()
        assertTrue(secondQueued)
        assertFalse(f.busy)
    }

    @Test fun `success without durable acceptance never silently discards the review`() = runTest {
        val f = Fixture(this)
        f.enqueue { Result.success(Unit) }
        runCurrent()
        assertEquals(1, f.recovered)
        assertEquals(1, f.errors.size)
        assertTrue(f.file.exists())
    }
}
