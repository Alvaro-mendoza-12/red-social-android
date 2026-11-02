package conectate.com.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import conectate.com.core.observability.AppMetrics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped

@ViewModelScoped
class AudioRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appMetrics: AppMetrics,
) : VoiceRecordingBackend {
    private val lock = Any()
    private var active: PcmRecordingSession? = null
    private var retainedPcm: File? = null
    @Volatile private var generation = 0L

    fun isRecording(): Boolean = synchronized(lock) { active?.isRecording == true }
    fun isPaused(): Boolean = synchronized(lock) { active?.isPaused == true }
    fun getRecordingFile(): File? = synchronized(lock) { active?.file ?: retainedPcm }

    override fun start(append: Boolean, onFailure: (Exception) -> Unit): File? = synchronized(lock) {
        active?.let { session ->
            if (!session.isRecording) return@synchronized null
            if (session.isPaused && !session.resume()) return@synchronized null
            return@synchronized session.file
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@synchronized null
        }
        if (append && retainedPcm?.isFile != true) return@synchronized null
        generation++
        val previous = retainedPcm
        retainedPcm = null
        var file: File? = null
        var input: PcmRecordingInput? = null
        var session: PcmRecordingSession? = null
        try {
            if (!append) previous?.delete()
            file = if (append) requireNotNull(previous) else File.createTempFile("raw_recording_", ".pcm", context.cacheDir)
            input = createInput()
            session = PcmRecordingSession(file, input) { error ->
                appMetrics.recordFailure("voice_note_pcm_write", error)
                onFailure(error)
            }
            active = session
            session.start()
            file
        } catch (error: Exception) {
            active = null
            if (session != null) session.discard()
            else {
                runCatching { input?.release() }
                file?.delete()
            }
            appMetrics.recordFailure("voice_note_record_start", error)
            null
        }
    }

    override fun pause(): Boolean = synchronized(lock) { active?.pause() == true }
    override fun resume(): Boolean = synchronized(lock) { active?.resume() == true }

    override fun detachFinalization(): VoiceRecordingFinalization? = synchronized(lock) {
        val session = active ?: return@synchronized null
        active = null
        session.requestStop()
        NativeFinalization(session, generation)
    }

    /** Compatibility for IO callers; the manager detaches before launching its IO job. */
    suspend fun stop(): File? = detachFinalization()?.finish()

    override fun cancel() {
        val previous = synchronized(lock) {
            generation++
            (active to retainedPcm).also { active = null; retainedPcm = null }
        }
        previous.first?.discard()
        previous.second?.delete()
    }

    private inner class NativeFinalization(
        private val session: PcmRecordingSession,
        private val owner: Long,
    ) : VoiceRecordingFinalization {
        private val started = AtomicBoolean(false)
        private val discarded = AtomicBoolean(false)
        private val output = AtomicReference<File?>(null)

        override suspend fun finish(): File? {
            check(started.compareAndSet(false, true)) { "Recording already finalized" }
            return try {
                withContext(Dispatchers.IO) {
                    val jobContext = currentCoroutineContext()
                    val checkActive = {
                        jobContext.ensureActive()
                        if (discarded.get() || generation != owner) throw CancellationException("Recording no longer active")
                    }
                    checkActive()
                    session.awaitClosed()
                    checkActive()
                    val file = AacVoiceEncoder(context.cacheDir).encode(session.file, checkActive)
                    output.set(file)
                    checkActive()
                    val retained = synchronized(lock) {
                        if (generation == owner && active == null && !discarded.get()) {
                            retainedPcm = session.transferFile()
                            retainedPcm != null
                        } else false
                    }
                    if (!retained) session.discard()
                    file
                }
            } catch (cancelled: CancellationException) {
                discard()
                throw cancelled
            } catch (error: Exception) {
                appMetrics.recordFailure("voice_note_record_finalize", error)
                discard()
                null
            }
        }

        override fun discard() {
            discarded.set(true)
            output.getAndSet(null)?.delete()
            session.discard()
        }
    }

    private fun createInput(): PcmRecordingInput {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is required")
        }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Invalid AudioRecord buffer size" }
        // Preserve the existing restoration of the app's microphone state after a call.
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let {
            if (it.isMicrophoneMute) it.isMicrophoneMute = false
        }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minimum * 4)
        var noise: NoiseSuppressor? = null
        var gain: AutomaticGainControl? = null
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
            // These optional effects must not prevent recording on unsupported vendor implementations.
            try {
                if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(recorder.audioSessionId)
                noise?.enabled = true
            } catch (error: Exception) { appMetrics.recordFailure("voice_note_noise_effect", error) }
            try {
                if (AutomaticGainControl.isAvailable()) gain = AutomaticGainControl.create(recorder.audioSessionId)
                gain?.enabled = true
            } catch (error: Exception) { appMetrics.recordFailure("voice_note_gain_effect", error) }
            val inputNoise = noise
            val inputGain = gain
            return object : PcmRecordingInput {
                override val bufferSize = minimum
                override fun start() {
                    recorder.startRecording()
                    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                }
                override fun stop() = recorder.stop()
                override fun read(buffer: ByteArray): Int = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                override fun release() {
                    runCatching { recorder.release() }
                    runCatching { inputNoise?.release() }
                    runCatching { inputGain?.release() }
                }
            }
        } catch (error: Exception) {
            runCatching { recorder.release() }
            runCatching { noise?.release() }
            runCatching { gain?.release() }
            throw error
        }
    }

    private companion object { const val SAMPLE_RATE = 44_100 }
}
