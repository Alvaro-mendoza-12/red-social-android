package conectate.com.presentation.messages

import android.os.SystemClock
import androidx.annotation.MainThread
import conectate.com.core.observability.AppMetrics
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.util.AudioRecorder
import conectate.com.util.VoiceRecordingBackend
import conectate.com.util.VoiceRecordingFinalization
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped

enum class VoiceState { IDLE, RECORDING, PAUSED, FINALIZING, REVIEWING }

@ViewModelScoped
@MainThread
class VoiceMessageManager internal constructor(
    private val audioRecorder: VoiceRecordingBackend,
    private val appMetrics: AppMetrics,
    private val managerScope: CoroutineScope,
    private val clock: () -> Long,
    private val playerFactory: () -> VoicePreviewPlayer,
    private val showError: (String) -> Unit,
) {
    @Inject constructor(audioRecorder: AudioRecorder, appMetrics: AppMetrics) : this(
        audioRecorder, appMetrics, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        SystemClock::elapsedRealtime, ::LocalVoicePreviewPlayer, { AppFeedbackBus.showError(it) },
    )

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()
    private val _playbackDurationLabel = MutableStateFlow("0:00")
    val playbackDurationLabel: StateFlow<String> = _playbackDurationLabel.asStateFlow()
    private val _isDraftPlaying = MutableStateFlow(false)
    val isDraftPlaying: StateFlow<Boolean> = _isDraftPlaying.asStateFlow()

    private var generation = 0L
    private var timerJob: Job? = null
    private var playbackJob: Job? = null
    private var mediaPlayer: VoicePreviewPlayer? = null
    private var playWhenPrepared: VoicePreviewPlayer? = null
    private var recordedFile: File? = null
    private var fileHandedOff = false
    private var accumulatedMs = 0L
    private var segmentStartedAt: Long? = null
    private var pending: Pending? = null
    private class Pending(val generation: Long, val duration: Long, val finalizer: VoiceRecordingFinalization) {
        var job: Deferred<File?>? = null
    }

    fun startRecording() {
        when (_state.value) {
            VoiceState.RECORDING, VoiceState.FINALIZING -> return
            VoiceState.PAUSED -> { resumeRecording(); return }
            else -> Unit
        }
        reset()
        startNative(append = false)
    }

    private fun startNative(append: Boolean) {
        val owner = generation
        val file = try {
            audioRecorder.start(append) { error ->
                managerScope.launch {
                    if (generation == owner && (_state.value == VoiceState.RECORDING || _state.value == VoiceState.PAUSED)) {
                        failRecording("voice_note_record_read", error)
                    }
                }
            }
        } catch (error: Exception) { failRecording("voice_note_record_start", error); return }
        if (file == null) {
            failRecording("voice_note_record_start")
            return
        }
        _state.value = VoiceState.RECORDING
        startTimer()
    }

    fun pauseRecording() {
        if (_state.value != VoiceState.RECORDING) return
        snapshotDuration()
        val paused = try { audioRecorder.pause() } catch (error: Exception) {
            failRecording("voice_note_record_pause", error); return
        }
        if (!paused) { failRecording("voice_note_record_pause"); return }
        _state.value = VoiceState.PAUSED
    }

    fun resumeRecording() {
        if (_state.value != VoiceState.PAUSED) return
        val resumed = try { audioRecorder.resume() } catch (error: Exception) {
            failRecording("voice_note_record_resume", error); return
        }
        if (!resumed) { failRecording("voice_note_record_resume"); return }
        _state.value = VoiceState.RECORDING
        startTimer()
    }

    fun resumeRecordingFromReview() {
        if (_state.value != VoiceState.REVIEWING) return
        generation++
        releasePreview()
        discardUnsharedFile()
        startNative(append = true)
    }

    private fun beginFinalization(): Pending? {
        if (_state.value != VoiceState.RECORDING && _state.value != VoiceState.PAUSED) return null
        if (pending != null) return null
        snapshotDuration()
        val finalizer = try { audioRecorder.detachFinalization() }
        catch (error: Exception) { failRecording("voice_note_record_finalize", error); return null }
        if (finalizer == null) { failRecording("voice_note_record_finalize"); return null }
        _state.value = VoiceState.FINALIZING
        return Pending(generation, _duration.value, finalizer).also { pending = it }
    }

    fun stopRecordingForReview() {
        val attempt = beginFinalization() ?: return
        val job = managerScope.async(start = CoroutineStart.LAZY) {
            try {
                val file = finish(attempt)
                if (current(attempt)) {
                    pending = null
                    if (file != null) {
                        recordedFile = file
                        fileHandedOff = false
                        _state.value = VoiceState.REVIEWING
                        preparePreview(file)
                    } else {
                        reset()
                    }
                } else file?.delete()
                file
            } catch (cancelled: CancellationException) {
                if (current(attempt)) reset()
                throw cancelled
            }
        }
        attempt.job = job
        if (current(attempt)) job.start() else { job.cancel(); attempt.finalizer.discard() }
    }

    /** The returned M4A belongs to the sender and survives resetting this manager. */
    suspend fun stopAndGetFile(): Pair<File?, Long> {
        val attempt = beginFinalization() ?: return null to _duration.value
        val job = managerScope.async(start = CoroutineStart.LAZY) { finish(attempt) }
        attempt.job = job
        if (current(attempt)) job.start() else { job.cancel(); attempt.finalizer.discard() }
        try {
            val file = job.await()
            currentCoroutineContext().ensureActive()
            if (!current(attempt)) { file?.delete(); return null to attempt.duration }
            pending = null
            // Reset does not discard the completed finalizer or the handed-off M4A.
            reset()
            return file to attempt.duration
        } catch (cancelled: CancellationException) {
            if (current(attempt)) reset()
            else attempt.finalizer.discard()
            throw cancelled
        }
    }

    private suspend fun finish(attempt: Pending): File? {
        var produced: File? = null
        var accepted = false
        try {
            produced = attempt.finalizer.finish()
            currentCoroutineContext().ensureActive()
            if (!current(attempt)) return null
            if (produced == null) {
                showError("No se pudo preparar el audio. Intenta grabarlo otra vez.")
                return null
            }
            if (attempt.duration <= 500L) {
                showError("La grabación es muy corta. Graba un poco más antes de enviarla.")
                return null
            }
            accepted = true
            return produced
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            appMetrics.recordFailure("voice_note_record_finalize", error)
            if (current(attempt)) showError("No se pudo preparar el audio. Intenta grabarlo otra vez.")
            return null
        } finally {
            if (!accepted) {
                produced?.delete()
                attempt.finalizer.discard()
            }
        }
    }

    private fun current(attempt: Pending) = generation == attempt.generation && pending === attempt

    fun cancelRecording() = reset()

    private fun preparePreview(file: File, playWhenReady: Boolean = false) {
        releasePreview()
        val owner = generation
        val player = try { playerFactory() } catch (error: Exception) { previewFailed(error); return }
        mediaPlayer = player
        if (playWhenReady) playWhenPrepared = player
        fun currentPlayer() = generation == owner && mediaPlayer === player && _state.value == VoiceState.REVIEWING
        try {
            player.prepare(file,
                onPrepared = {
                    if (currentPlayer()) {
                        _playbackDurationLabel.value = formatDuration(player.duration.toLong())
                        if (playWhenPrepared === player) {
                            playWhenPrepared = null
                            playPreview()
                        }
                    }
                },
                onCompletion = { if (currentPlayer()) { _playbackProgress.value = 1f; _isDraftPlaying.value = false; playbackJob?.cancel() } },
                onError = { error -> if (currentPlayer()) previewFailed(error) },
            )
        } catch (error: Exception) { if (currentPlayer()) previewFailed(error) }
    }

    fun playPreview() {
        if (_state.value != VoiceState.REVIEWING) return
        val player = mediaPlayer ?: run {
            val file = recordedFile?.takeIf { it.isFile }
            if (file == null) {
                showError("No pudimos recuperar la grabación. Graba otra copia.")
            } else {
                _playbackDurationLabel.value = "Preparando…"
                preparePreview(file, playWhenReady = true)
            }
            return
        }
        if (!player.prepared) {
            playWhenPrepared = player
            _playbackDurationLabel.value = "Preparando…"
            return
        }
        playWhenPrepared = null
        if (player.position >= player.duration && player.duration > 0) player.seekTo(0)
        if (player.play()) {
            _isDraftPlaying.value = true
            startPlaybackTracker(player)
        }
    }

    fun pausePreview() {
        playWhenPrepared = null
        if (_state.value != VoiceState.REVIEWING) return
        mediaPlayer?.takeIf { it.prepared }?.pause()
        _isDraftPlaying.value = false
        playbackJob?.cancel()
    }

    fun seekPreview(fraction: Float) {
        if (_state.value != VoiceState.REVIEWING || !fraction.isFinite()) return
        val player = mediaPlayer?.takeIf { it.prepared } ?: return
        val bounded = fraction.coerceIn(0f, 1f)
        if (player.seekTo((player.duration * bounded).toInt())) _playbackProgress.value = bounded
    }

    fun isPreviewPlaying(): Boolean = _state.value == VoiceState.REVIEWING && mediaPlayer?.playing == true

    private fun previewFailed(error: Exception) {
        releasePreview()
        _playbackDurationLabel.value = formatDuration(_duration.value)
        appMetrics.recordFailure("voice_note_preview", error)
        showError("No se pudo reproducir la grabación. Puedes enviarla o grabar otra copia.")
    }

    private fun releasePreview() {
        playbackJob?.cancel()
        val previous = mediaPlayer
        mediaPlayer = null
        playWhenPrepared = null
        _isDraftPlaying.value = false
        previous?.release()
    }

    private fun startTimer() {
        timerJob?.cancel()
        segmentStartedAt = clock()
        val owner = generation
        timerJob = managerScope.launch {
            while (generation == owner && _state.value == VoiceState.RECORDING) {
                _duration.value = accumulatedMs + (clock() - requireNotNull(segmentStartedAt)).coerceAtLeast(0L)
                delay(100L)
            }
        }
    }

    private fun snapshotDuration() {
        timerJob?.cancel()
        segmentStartedAt?.let { accumulatedMs += (clock() - it).coerceAtLeast(0L) }
        segmentStartedAt = null
        _duration.value = accumulatedMs
    }

    private fun startPlaybackTracker(player: VoicePreviewPlayer) {
        playbackJob?.cancel()
        val owner = generation
        playbackJob = managerScope.launch {
            while (generation == owner && mediaPlayer === player && _state.value == VoiceState.REVIEWING && player.playing) {
                if (player.duration > 0) _playbackProgress.value = (player.position.toFloat() / player.duration).coerceIn(0f, 1f)
                delay(50L)
            }
        }
    }

    private fun failRecording(metric: String, error: Exception? = null) {
        appMetrics.recordFailure(metric, error)
        reset()
        showError("No se pudo grabar el audio. Revisa el permiso del micrófono e inténtalo otra vez.")
    }

    private fun discardUnsharedFile() {
        if (!fileHandedOff) recordedFile?.delete()
        recordedFile = null
        fileHandedOff = false
    }

    fun reset() {
        generation++
        val old = pending
        pending = null
        old?.job?.cancel()
        old?.finalizer?.discard()
        timerJob?.cancel()
        releasePreview()
        audioRecorder.cancel()
        discardUnsharedFile()
        accumulatedMs = 0L
        segmentStartedAt = null
        _duration.value = 0L
        _playbackProgress.value = 0f
        _playbackDurationLabel.value = "0:00"
        _state.value = VoiceState.IDLE
    }

    fun formatDuration(ms: Long): String {
        val seconds = if (ms > 0L) (ms / 1_000L).coerceAtLeast(1L) else 0L
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /** Handoff: asynchronous upload may still read this file after reset or dialog disposal. */
    fun getCurrentFile(): File? = recordedFile.also { if (it != null) fileHandedOff = true }

    internal fun completeQueuedSubmission(file: File) {
        if (recordedFile == file) reset()
    }

    /** A local queue failure can restore drag-to-send, but cannot revive a replaced draft. */
    internal fun recoveryForUnqueuedSubmission(file: File, durationMs: Long): () -> Unit {
        val owner = generation
        return recovery@{
            if (generation != owner) return@recovery
            if (recordedFile == file && _state.value == VoiceState.REVIEWING) {
                fileHandedOff = false
            } else if (_state.value == VoiceState.IDLE) {
                recordedFile = file
                fileHandedOff = false
                accumulatedMs = durationMs
                _duration.value = durationMs
                _state.value = VoiceState.REVIEWING
                preparePreview(file)
            }
        }
    }

    fun stopRecording() = stopRecordingForReview()
    fun getRecordedFile(): File? = getCurrentFile()
    fun clearRecording() = reset()
}
