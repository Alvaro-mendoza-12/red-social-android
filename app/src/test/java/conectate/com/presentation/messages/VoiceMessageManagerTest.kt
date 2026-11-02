package conectate.com.presentation.messages

import conectate.com.core.observability.AppMetrics
import conectate.com.core.observability.MetricAlert
import conectate.com.util.VoiceRecordingBackend
import conectate.com.util.VoiceRecordingFinalization
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceMessageManagerTest {
    @get:Rule val temp = TemporaryFolder()

    private class Finalization(val ignoreCancellation: Boolean = false) : VoiceRecordingFinalization {
        val result = CompletableDeferred<File?>()
        var finishes = 0
        var discards = 0
        override suspend fun finish(): File? {
            finishes++
            return if (ignoreCancellation) withContext(NonCancellable) { result.await() } else result.await()
        }
        override fun discard() { discards++ }
    }
    private class Backend(private val file: File) : VoiceRecordingBackend {
        val finalizers = ArrayDeque<Finalization>()
        var starts = 0
        var resumes = 0
        var detaches = 0
        var cancels = 0
        var pauseWorks = true
        var startWorks = true
        var active = false
        var lastAppend = false
        var readFailure: ((Exception) -> Unit)? = null
        override fun start(append: Boolean, onFailure: (Exception) -> Unit): File? {
            starts++; lastAppend = append; readFailure = onFailure
            active = startWorks
            return file.takeIf { startWorks }
        }
        override fun pause() = pauseWorks
        override fun resume(): Boolean { resumes++; return true }
        override fun detachFinalization(): VoiceRecordingFinalization? {
            detaches++
            if (!active) return null
            active = false
            return finalizers.pollFirst()
        }
        override fun cancel() { cancels++; active = false }
    }
    private class Preview : VoicePreviewPlayer {
        override var prepared = false
        override var playing = false
        override var duration = 2_000
        override var position = 0
        var preparedCallback: () -> Unit = {}
        var completedCallback: () -> Unit = {}
        var errorCallback: (Exception) -> Unit = {}
        val seeks = mutableListOf<Int>()
        var releases = 0
        var plays = 0
        override fun prepare(file: File, onPrepared: () -> Unit, onCompletion: () -> Unit, onError: (Exception) -> Unit) {
            preparedCallback = onPrepared; completedCallback = onCompletion; errorCallback = onError
        }
        override fun play(): Boolean { plays++; playing = true; return true }
        override fun pause() { playing = false }
        override fun seekTo(position: Int): Boolean { seeks += position; this.position = position; return true }
        override fun release() { releases++; playing = false; prepared = false }
        fun ready() { prepared = true; preparedCallback() }
    }
    private object Metrics : AppMetrics {
        override val alerts = emptyFlow<MetricAlert>()
        override fun incrementCounter(metric: String, delta: Long, attributes: Map<String, String>) = Unit
        override fun recordLatency(metric: String, durationMs: Long, attributes: Map<String, String>) = Unit
        override fun recordFailure(metric: String, throwable: Throwable?, attributes: Map<String, String>) = Unit
    }
    private inner class Fixture(scope: TestScope, ignoreCancellation: Boolean = false) {
        val backend = Backend(temp.newFile())
        val finalizer = Finalization(ignoreCancellation).also { backend.finalizers.add(it) }
        val errors = mutableListOf<String>()
        val previews = mutableListOf<Preview>()
        val manager = VoiceMessageManager(backend, Metrics, scope.backgroundScope,
            { scope.testScheduler.currentTime }, { Preview().also { previews += it } }, { errors += it })
        fun output() = temp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
    }
    private fun TestScope.begin(fixture: Fixture, duration: Long = 751) {
        fixture.manager.startRecording()
        advanceTimeBy(duration)
    }
    private fun TestScope.review(fixture: Fixture): File {
        begin(fixture)
        fixture.manager.stopRecordingForReview()
        runCurrent()
        val file = fixture.output()
        fixture.finalizer.result.complete(file)
        runCurrent()
        assertEquals(VoiceState.REVIEWING, fixture.manager.state.value)
        return file
    }

    @Test fun `finalization cannot be resumed or started twice`() = runTest {
        val f = Fixture(this)
        begin(f)
        f.manager.stopRecordingForReview()
        runCurrent()
        assertEquals(VoiceState.FINALIZING, f.manager.state.value)
        f.manager.resumeRecording()
        f.manager.startRecording()
        f.manager.stopRecordingForReview()
        assertEquals(1, f.backend.starts)
        assertEquals(0, f.backend.resumes)
        assertEquals(1, f.backend.detaches)
        assertEquals(1, f.finalizer.finishes)
        f.finalizer.result.complete(f.output())
        runCurrent()
        assertEquals(VoiceState.REVIEWING, f.manager.state.value)
    }

    @Test fun `a late encoding result cannot replace a new recording and is deleted`() = runTest {
        val f = Fixture(this, ignoreCancellation = true)
        begin(f)
        f.manager.stopRecordingForReview()
        runCurrent()
        f.manager.reset()
        f.manager.startRecording()
        val obsolete = f.output()
        f.finalizer.result.complete(obsolete)
        runCurrent()
        assertEquals(VoiceState.RECORDING, f.manager.state.value)
        assertFalse(obsolete.exists())
        assertTrue(f.errors.isEmpty())
    }

    @Test fun `late encoding failure does not clear the new session or show its error`() = runTest {
        val f = Fixture(this, ignoreCancellation = true)
        begin(f)
        f.manager.stopRecordingForReview()
        runCurrent()
        f.manager.reset()
        f.manager.startRecording()
        f.finalizer.result.completeExceptionally(IOException("old encoder"))
        runCurrent()
        assertEquals(VoiceState.RECORDING, f.manager.state.value)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun `encoding failure preserves a recoverable idle state and tells the user`() = runTest {
        val f = Fixture(this)
        begin(f)
        f.manager.stopRecordingForReview()
        runCurrent()
        f.finalizer.result.completeExceptionally(IOException("encode failed"))
        runCurrent()
        assertEquals(VoiceState.IDLE, f.manager.state.value)
        assertEquals(1, f.errors.size)
        assertTrue(f.finalizer.discards > 0)
    }

    @Test fun `short recording is explained and its unusable output removed`() = runTest {
        val f = Fixture(this)
        begin(f, 499)
        f.manager.stopRecordingForReview()
        runCurrent()
        val file = f.output()
        f.finalizer.result.complete(file)
        runCurrent()
        assertEquals(VoiceState.IDLE, f.manager.state.value)
        assertFalse(file.exists())
        assertTrue(f.errors.single().contains("corta"))
    }

    @Test fun `drag to send hands off an output that survives resets`() = runTest {
        val f = Fixture(this)
        begin(f)
        val sending = async { f.manager.stopAndGetFile() }
        runCurrent()
        val file = f.output()
        f.finalizer.result.complete(file)
        runCurrent()
        assertEquals(file to 751L, sending.await())
        assertEquals(VoiceState.IDLE, f.manager.state.value)
        f.manager.reset()
        assertTrue(file.exists())
    }

    @Test fun `cancelled sender cannot leak a late result or reset the next recording`() = runTest {
        val f = Fixture(this, ignoreCancellation = true)
        begin(f)
        val sender = launch { f.manager.stopAndGetFile() }
        runCurrent()
        sender.cancel()
        runCurrent()
        f.manager.startRecording()
        val file = f.output()
        f.finalizer.result.complete(file)
        runCurrent()
        assertFalse(file.exists())
        assertEquals(VoiceState.RECORDING, f.manager.state.value)
    }

    @Test fun `pause gaps are excluded and final duration includes the last fraction of a tick`() = runTest {
        val f = Fixture(this)
        begin(f, 751)
        f.manager.pauseRecording()
        assertEquals(751L, f.manager.duration.value)
        advanceTimeBy(10_000)
        f.manager.resumeRecording()
        advanceTimeBy(249)
        f.manager.stopRecordingForReview()
        assertEquals(1_000L, f.manager.duration.value)
        runCurrent()
        f.finalizer.result.complete(f.output())
        runCurrent()
        assertEquals(1_000L, f.manager.duration.value)
    }

    @Test fun `discarding an unshared review removes its private M4A`() = runTest {
        val f = Fixture(this)
        val file = review(f)
        f.manager.cancelRecording()
        assertFalse(file.exists())
        assertEquals(VoiceState.IDLE, f.manager.state.value)
    }

    @Test fun `file taken by an asynchronous uploader survives review disposal`() = runTest {
        val f = Fixture(this)
        val file = review(f)
        assertEquals(file, f.manager.getCurrentFile())
        f.manager.reset()
        assertTrue(file.exists())
    }

    @Test fun `continuing a review appends PCM and removes the stale unshared preview`() = runTest {
        val f = Fixture(this)
        val oldFile = review(f)
        f.manager.resumeRecordingFromReview()
        assertTrue(f.backend.lastAppend)
        assertFalse(oldFile.exists())
        assertEquals(VoiceState.RECORDING, f.manager.state.value)
        assertEquals(751L, f.manager.duration.value)
    }

    @Test fun `seeking before prepare or with nonfinite values never touches a native player`() = runTest {
        val f = Fixture(this)
        review(f)
        val player = f.previews.single()
        f.manager.pausePreview()
        f.manager.seekPreview(0.5f)
        assertTrue(player.seeks.isEmpty())
        player.ready()
        f.manager.seekPreview(Float.NaN)
        f.manager.seekPreview(Float.POSITIVE_INFINITY)
        assertTrue(player.seeks.isEmpty())
        f.manager.seekPreview(-1f)
        f.manager.seekPreview(3f)
        assertEquals(listOf(0, 2_000), player.seeks)
        assertEquals(1f, f.manager.playbackProgress.value, 0f)
    }

    @Test fun `released preview callbacks cannot mutate another recording`() = runTest {
        val f = Fixture(this)
        review(f)
        val oldPlayer = f.previews.single()
        f.manager.reset()
        f.manager.startRecording()
        oldPlayer.ready()
        oldPlayer.completedCallback()
        oldPlayer.errorCallback(IOException("old player"))
        assertEquals(VoiceState.RECORDING, f.manager.state.value)
        assertEquals("0:00", f.manager.playbackDurationLabel.value)
        assertEquals(0f, f.manager.playbackProgress.value, 0f)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun `native recording failure and failed pause are visible and clean up`() = runTest {
        val f = Fixture(this)
        f.backend.startWorks = false
        f.manager.startRecording()
        assertEquals(VoiceState.IDLE, f.manager.state.value)
        assertEquals(1, f.errors.size)
        f.backend.startWorks = true
        f.manager.startRecording()
        f.backend.pauseWorks = false
        f.manager.pauseRecording()
        assertEquals(VoiceState.IDLE, f.manager.state.value)
        assertEquals(2, f.errors.size)
    }

    @Test fun `read failure from a replaced recording is ignored but the current one is handled`() = runTest {
        val f = Fixture(this)
        f.manager.startRecording()
        val oldFailure = requireNotNull(f.backend.readFailure)
        f.manager.reset()
        f.manager.startRecording()
        oldFailure(IOException("old read"))
        runCurrent()
        assertEquals(VoiceState.RECORDING, f.manager.state.value)
        assertTrue(f.errors.isEmpty())
        requireNotNull(f.backend.readFailure)(IOException("active read"))
        runCurrent()
        assertEquals(VoiceState.IDLE, f.manager.state.value)
        assertEquals(1, f.errors.size)
    }

    @Test fun `disposing another screen does not reset the current screens recording`() = runTest {
        val oldScreen = Fixture(this)
        val currentScreen = Fixture(this)
        oldScreen.manager.startRecording()
        currentScreen.manager.startRecording()
        oldScreen.manager.reset()
        assertEquals(VoiceState.RECORDING, currentScreen.manager.state.value)
        assertTrue(currentScreen.backend.active)
        assertEquals(1, currentScreen.backend.cancels) // Its own initial reset, not the old screen's cleanup.
    }

    @Test fun `an unqueued drag to send can be restored for retry`() = runTest {
        val f = Fixture(this)
        begin(f)
        val sending = async { f.manager.stopAndGetFile() }
        runCurrent()
        val file = f.output()
        f.finalizer.result.complete(file)
        runCurrent()
        assertEquals(file, sending.await().first)
        val recover = f.manager.recoveryForUnqueuedSubmission(file, 751L)
        recover()
        assertEquals(VoiceState.REVIEWING, f.manager.state.value)
        assertEquals(751L, f.manager.duration.value)
        assertEquals(file, f.manager.getCurrentFile())
    }

    @Test fun `old local queue failure cannot revive audio over a new recording`() = runTest {
        val f = Fixture(this)
        val file = review(f)
        val recover = f.manager.recoveryForUnqueuedSubmission(file, 751L)
        f.manager.reset()
        f.manager.startRecording()
        recover()
        assertEquals(VoiceState.RECORDING, f.manager.state.value)
    }

    @Test fun `failed unqueued review relinquishes its handoff so cancellation removes the file`() = runTest {
        val f = Fixture(this)
        val file = review(f)
        f.manager.getCurrentFile()
        val recover = f.manager.recoveryForUnqueuedSubmission(file, 751L)
        recover()
        f.manager.reset()
        assertFalse(file.exists())
    }

    @Test fun `old queue acceptance cannot clear a new recording`() = runTest {
        val f = Fixture(this)
        val file = review(f)
        f.manager.getCurrentFile()
        f.manager.reset()
        f.manager.startRecording()
        f.manager.completeQueuedSubmission(file)
        assertEquals(VoiceState.RECORDING, f.manager.state.value)
    }

    @Test fun `a valid subsecond note has a usable duration caption instead of zero`() = runTest {
        val f = Fixture(this)
        assertEquals(1_000L, conectate.com.util.audioDurationMillis(f.manager.formatDuration(751L)))
        assertEquals("0:00", f.manager.formatDuration(0L))
        assertEquals("0:00", f.manager.formatDuration(-1L))
    }

    @Test fun `one play tap while preparing starts playback when that player is ready`() = runTest {
        val f = Fixture(this)
        review(f)
        val player = f.previews.single()
        f.manager.playPreview()
        f.manager.playPreview()
        assertEquals(0, player.plays)
        assertEquals("Preparando…", f.manager.playbackDurationLabel.value)
        player.ready()
        assertEquals(1, player.plays)
        assertTrue(f.manager.isDraftPlaying.value)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun `pausing before prepare prevents delayed autoplay in a hidden screen`() = runTest {
        val f = Fixture(this)
        review(f)
        val player = f.previews.single()
        f.manager.playPreview()
        f.manager.pausePreview()
        player.ready()
        assertEquals(0, player.plays)
        assertFalse(f.manager.isDraftPlaying.value)
    }

    @Test fun `play after a failed preview creates a fresh attempt and starts it once ready`() = runTest {
        val f = Fixture(this)
        review(f)
        val old = f.previews.single()
        old.errorCallback(IOException("decoder busy"))
        assertEquals(1, f.errors.size)
        f.manager.playPreview()
        assertEquals(2, f.previews.size)
        val next = f.previews.last()
        old.ready()
        assertEquals(0, old.plays)
        next.ready()
        assertEquals(1, next.plays)
        assertTrue(f.manager.isDraftPlaying.value)
    }
}
