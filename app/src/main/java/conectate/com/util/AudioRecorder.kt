package conectate.com.util

import android.content.Context
import android.media.*
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // --- Configuration ---
    // Using 44.1kHz for maximum compatibility across all Android devices
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    
    // --- State ---
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    
    @Volatile
    private var isRecording = false
    @Volatile
    private var isPaused = false
    @Volatile
    private var writerThread: Thread? = null
    
    // Temporary raw PCM file
    private var pcmFile: File? = null
    
    // Playback (Preview)
    private var mediaPlayer: MediaPlayer? = null
    
    // --- Public State Access ---
    fun isRecording(): Boolean = isRecording
    fun isPaused(): Boolean = isPaused
    fun getRecordingFile(): File? = pcmFile

    // --- Recording Methods ---

    fun start(append: Boolean = false): File? {
        if (isRecording) {
            if (isPaused) {
               resume()
               return pcmFile
            }
            return pcmFile
        }
        
        try {
            val outputDir = context.cacheDir
            if (!append || pcmFile == null || !pcmFile!!.exists()) {
                pcmFile = File.createTempFile("raw_recording_", ".pcm", outputDir)
            }
            
            // Initialize AudioRecord
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            
            // Ensure system microphone is NOT muted (might have been left muted by another component)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.isMicrophoneMute) {
                android.util.Log.w("AudioRecorder", "🎤 System mic was MUTED. Unmuting now...")
                audioManager.isMicrophoneMute = false
            }

            // VOICE_RECOGNITION is often more reliable for voice notes and triggers privacy dot better
            val audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
            
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                android.util.Log.e("AudioRecorder", "Permission RECORD_AUDIO not granted")
                return null
            }

            if (minBufferSize <= 0) {
                android.util.Log.e("AudioRecorder", "Invalid minBufferSize: $minBufferSize")
                return null
            }

            audioRecord = AudioRecord(audioSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, minBufferSize * 4)
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                android.util.Log.e("AudioRecorder", "AudioRecord initialization FAILED. State: ${audioRecord?.state}")
                return null
            }

            // --- Apply Effects ---
            val sessionId = audioRecord!!.audioSessionId
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
            }
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(sessionId)
                automaticGainControl?.enabled = true
            }

            audioRecord?.startRecording()
            isRecording = true
            isPaused = false
            
            startWriterThread()
            
            return pcmFile
        } catch (e: Exception) {
            e.printStackTrace()
            stop() // Clean up
            return null
        }
    }

    fun pause() {
        if (!isRecording || isPaused) return
        isPaused = true
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resume() {
        if (!isRecording || !isPaused) return
        
        try {
            audioRecord?.startRecording()
            isPaused = false
            
            startWriterThread()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Returns the temp file encoded as M4A (or PCM if encode fails/ignored)
    fun stop(): File? {
        if (!isRecording) return null
        
        isRecording = false
        isPaused = false
        
        try {
            audioRecord?.stop()
            awaitWriterCompletion()
            audioRecord?.release()
            noiseSuppressor?.release()
            automaticGainControl?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioRecord = null
            noiseSuppressor = null
            automaticGainControl = null
        }
        
        // Return encoded file
        return if (pcmFile != null && pcmFile!!.exists()) {
             encodeToAac(pcmFile!!)
        } else {
             null
        }
    }
    
    // Allows discarding current recording
    fun cancel() {
        isRecording = false
        isPaused = false
        val fileToDelete = pcmFile
        val threadToAwait = writerThread
        try {
            audioRecord?.stop()
            audioRecord?.release()
            noiseSuppressor?.release()
            automaticGainControl?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
        pcmFile = null
        writerThread = null
        Thread {
            runCatching { threadToAwait?.join(WRITER_JOIN_TIMEOUT_MS) }
            runCatching { fileToDelete?.delete() }
        }.apply {
            name = "voice-note-cleanup"
            isDaemon = true
            start()
        }
    }

    private fun startWriterThread() {
        writerThread = Thread {
            writePcmDataToFile()
        }.apply {
            name = "voice-note-pcm-writer"
            start()
        }
    }

    private fun awaitWriterCompletion() {
        val activeWriter = writerThread
        if (activeWriter != null && activeWriter !== Thread.currentThread()) {
            runCatching { activeWriter.join(WRITER_JOIN_TIMEOUT_MS) }
            if (activeWriter.isAlive) {
                android.util.Log.w("AudioRecorder", "El escritor PCM tardó demasiado; se interrumpe de forma segura.")
                runCatching { activeWriter.interrupt() }
                runCatching { activeWriter.join(500L) }
            }
        }
        writerThread = null
    }

    private fun writePcmDataToFile() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val data = ByteArray(bufferSize)
        val file = pcmFile ?: return
        
        try {
            var totalBytesRead = 0L
            FileOutputStream(file, true).use { output ->
                while (isRecording && !isPaused) {
                    val read = audioRecord?.read(data, 0, bufferSize) ?: 0
                    if (read > 0) {
                        output.write(data, 0, read)
                        totalBytesRead += read
                    } else if (read < 0) {
                        android.util.Log.e("AudioRecorder", "Error reading from AudioRecord: $read")
                    }
                }
                output.flush()
            }
            android.util.Log.d("AudioRecorder", "Finished writing PCM. Total bytes: $totalBytesRead")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // --- Playback (Preview) ---
    
    // Generates a preview file WITHOUT stopping the session logic directly, 
    // although technically we are reading the file while it might be written to?
    // Safe-ish as long as we are paused.
    // If we want to preview, we usually PAUSE first.
    suspend fun getPreviewFile(): File? = withContext(Dispatchers.IO) {
        if (pcmFile == null || !pcmFile!!.exists()) return@withContext null
        encodeToAac(pcmFile!!)
    }

    // --- Encoding (PCM -> M4A) ---
    private fun encodeToAac(inputFile: File): File? {
        if (!inputFile.exists() || inputFile.length() == 0L) return null
        
        val outputDir = context.cacheDir
        val outputFile = File.createTempFile("voice_message_", ".m4a", outputDir)
        var inputStream: FileInputStream? = null
        var mediaMuxer: MediaMuxer? = null
        var codec: MediaCodec? = null
        var muxerStarted = false

        try {
            val fis = FileInputStream(inputFile).also { inputStream = it }
            
            val activeMuxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            ).also { mediaMuxer = it }
            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
            ) ?: "OMX.google.aac.encoder"
            
            val activeCodec = MediaCodec.createByCodecName(codecName).also { codec = it }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            
            activeCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            activeCodec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var inputDone = false
            var outputDone = false
            
            val buffer = ByteArray(4096)
            var presentationTimeUs = 0L
            val deadlineMs = android.os.SystemClock.elapsedRealtime() + ENCODER_TIMEOUT_MS
            
            while (!outputDone) {
                if (android.os.SystemClock.elapsedRealtime() > deadlineMs) {
                    throw IllegalStateException("El codificador de audio no terminó a tiempo.")
                }
                if (!inputDone) {
                    val index = activeCodec.dequeueInputBuffer(2_000)
                    if (index >= 0) {
                        val inputBuffer = activeCodec.getInputBuffer(index)
                        inputBuffer?.clear()
                        
                        val read = fis.read(buffer)
                        if (read == -1) {
                            activeCodec.queueInputBuffer(
                                index,
                                0,
                                0,
                                presentationTimeUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            if (inputBuffer == null) {
                                throw IllegalStateException("El codificador no entregó un búfer de entrada.")
                            }
                            inputBuffer.put(buffer, 0, read)
                            activeCodec.queueInputBuffer(index, 0, read, presentationTimeUs, 0)
                            presentationTimeUs += read.toLong() * 1_000_000L / sampleRateByteSize()
                        }
                    }
                }
                
                val encoderStatus = activeCodec.dequeueOutputBuffer(bufferInfo, 2_000)
                if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        throw IllegalStateException("El formato de audio cambió más de una vez.")
                    }
                    trackIndex = activeMuxer.addTrack(activeCodec.outputFormat)
                    activeMuxer.start()
                    muxerStarted = true
                } else if (encoderStatus >= 0) {
                    val outputBuffer = activeCodec.getOutputBuffer(encoderStatus)
                    if (outputBuffer != null) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                         
                        if (bufferInfo.size != 0 && trackIndex >= 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            activeMuxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                         
                        activeCodec.releaseOutputBuffer(encoderStatus, false)
                         
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    } else {
                        activeCodec.releaseOutputBuffer(encoderStatus, false)
                    }
                }
            }

            return outputFile.takeIf { it.exists() && it.length() > 0L }
        } catch (e: Exception) {
            android.util.Log.e("AudioRecorder", "No se pudo codificar el audio", e)
            runCatching { outputFile.delete() }
            return null
        } finally {
            runCatching { inputStream?.close() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) {
                runCatching { mediaMuxer?.stop() }
            }
            runCatching { mediaMuxer?.release() }
        }
    }
    
    private fun sampleRateByteSize(): Int {
        // 16bit = 2 bytes * sampleRate
        return SAMPLE_RATE * 2
    }

    private companion object {
        const val WRITER_JOIN_TIMEOUT_MS = 5_000L
        const val ENCODER_TIMEOUT_MS = 90_000L
    }
}
