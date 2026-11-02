package conectate.com.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal interface PcmRecordingInput {
    val bufferSize: Int
    fun start()
    fun stop()
    fun read(buffer: ByteArray): Int
    fun release()
}

/** One writer for the entire recording; pausing never starts a competing writer. */
internal class PcmRecordingSession(
    val file: File,
    private val input: PcmRecordingInput,
    private val onFailure: (Exception) -> Unit,
) {
    private enum class Phase { NEW, RECORDING, PAUSED, STOPPING, ENDED }
    private val lock = Object()
    private var phase = Phase.NEW
    private var segment = 0L
    private var discardRequested = false
    private var fileTransferred = false
    private val released = AtomicBoolean(false)
    @Volatile private var failure: Exception? = null
    private val writer = Thread(::writePcm, "voice-note-pcm-writer").apply { isDaemon = true }

    val isRecording: Boolean get() = synchronized(lock) { phase == Phase.RECORDING || phase == Phase.PAUSED }
    val isPaused: Boolean get() = synchronized(lock) { phase == Phase.PAUSED }

    fun start() = synchronized(lock) {
        check(phase == Phase.NEW)
        require(input.bufferSize > 0)
        input.start()
        phase = Phase.RECORDING
        writer.start()
    }

    fun pause(): Boolean = synchronized(lock) {
        if (phase != Phase.RECORDING) return@synchronized false
        phase = Phase.PAUSED
        segment++
        try {
            input.stop()
            true
        } catch (error: Exception) {
            failControl(error)
            false
        }
    }

    fun resume(): Boolean = synchronized(lock) {
        if (phase != Phase.PAUSED) return@synchronized false
        try {
            input.start()
            phase = Phase.RECORDING
            segment++
            lock.notifyAll()
            true
        } catch (error: Exception) {
            failControl(error)
            false
        }
    }

    private fun failControl(error: Exception) {
        failure = error
        phase = Phase.STOPPING
        segment++
        lock.notifyAll()
    }

    /** Detach synchronously before switching dispatchers or starting a new recording. */
    fun requestStop() = synchronized(lock) {
        if (phase == Phase.ENDED || phase == Phase.STOPPING) return@synchronized
        phase = Phase.STOPPING
        segment++
        lock.notifyAll()
        // A failed native stop is followed by release if the blocked read cannot finish.
        try { input.stop() } catch (_: Exception) { }
    }

    /** Only IO/cleanup callers wait. Encoding must never race an open PCM writer. */
    fun awaitClosed() {
        check(Thread.currentThread() !== writer)
        writer.join(5_000L)
        if (writer.isAlive) {
            releaseInput()
            writer.interrupt()
            writer.join(500L)
        }
        check(!writer.isAlive) { "The PCM writer did not stop" }
        releaseInput()
        failure?.let { throw it }
    }

    fun transferFile(): File? = synchronized(lock) {
        if (discardRequested || failure != null || phase != Phase.ENDED) null
        else file.also { fileTransferred = true }
    }

    fun discard() {
        synchronized(lock) {
            if (discardRequested) return
            discardRequested = true
        }
        requestStop()
        Thread({
            try { awaitClosed() } catch (_: Exception) { /* Writer finally still owns cleanup. */ }
            synchronized(lock) { if (!writer.isAlive && !fileTransferred) file.delete() }
        }, "voice-note-cleanup").apply { isDaemon = true; start() }
    }

    private fun releaseInput() {
        if (released.compareAndSet(false, true)) {
            try { input.release() } catch (_: Exception) { /* Best-effort resource cleanup. */ }
        }
    }

    private fun writePcm() {
        try {
            val buffer = ByteArray(input.bufferSize)
            FileOutputStream(file, true).use { output ->
                while (true) {
                    val readSegment = synchronized(lock) {
                        while (phase == Phase.PAUSED) lock.wait()
                        if (phase != Phase.RECORDING) return@use
                        segment
                    }
                    val count = try { input.read(buffer) }
                    catch (error: Exception) {
                        if (synchronized(lock) { phase != Phase.RECORDING || segment != readSegment }) continue
                        throw error
                    }
                    synchronized(lock) {
                        // A read interrupted by pause/stop belongs to the old segment.
                        if (phase == Phase.RECORDING && segment == readSegment) {
                            if (count <= 0 || count > buffer.size) throw IOException("AudioRecord read failed ($count)")
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        } catch (error: Exception) {
            val unexpected = synchronized(lock) {
                if (phase == Phase.STOPPING && error is InterruptedException) false
                else { failure = error; true }
            }
            if (unexpected) onFailure(error)
        } finally {
            releaseInput()
            synchronized(lock) {
                phase = Phase.ENDED
                lock.notifyAll()
                if (discardRequested && !fileTransferred) file.delete()
            }
        }
    }
}
