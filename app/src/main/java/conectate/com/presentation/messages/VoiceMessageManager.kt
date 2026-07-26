package conectate.com.presentation.messages

import android.media.MediaPlayer
import android.net.Uri
import conectate.com.util.AudioRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class VoiceState {
    IDLE,
    RECORDING,
    PAUSED,     // Recording paused
    REVIEWING   // Stopped recording, reviewing before send
}

@Singleton
class VoiceMessageManager @Inject constructor(
    private val audioRecorder: AudioRecorder
) {
    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow() // In milliseconds

    // Preview Playback State
    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()
    
    private val _playbackDurationLabel = MutableStateFlow("0:00")
    val playbackDurationLabel: StateFlow<String> = _playbackDurationLabel.asStateFlow()

    private val _isDraftPlaying = MutableStateFlow(false)
    val isDraftPlaying: StateFlow<Boolean> = _isDraftPlaying.asStateFlow()
    
    // Internal
    private var timerJob: Job? = null
    private var playbackJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recordedFile: File? = null
    @Volatile
    private var isFinalizing = false
    @Volatile
    private var isPreviewPrepared = false

    fun setScope(coroutineScope: CoroutineScope) {
        // Obsolete but kept for compatibility - now using internal managerScope
    }

    // --- Recording Control ---

    fun startRecording() {
        if (_state.value == VoiceState.RECORDING) return
        
        // If retrieving from pause
        if (_state.value == VoiceState.PAUSED) {
            resumeRecording()
            return
        }

        // New Recording
        reset()
        val file = audioRecorder.start()
        if (file != null) {
            _state.value = VoiceState.RECORDING
            startTimer()
        } else {
             _state.value = VoiceState.IDLE
        }
    }

    fun pauseRecording() {
        if (_state.value != VoiceState.RECORDING) return
        
        audioRecorder.pause()
        _state.value = VoiceState.PAUSED
        timerJob?.cancel()
    }

    fun resumeRecording() {
        if (_state.value != VoiceState.PAUSED) return
        
        audioRecorder.resume()
        _state.value = VoiceState.RECORDING
        startTimer()
    }

    // New method to resume from Review state (appending to previous session)
    fun resumeRecordingFromReview() {
        if (_state.value != VoiceState.REVIEWING) return
        
        // Stop playback resources
        pausePreview()
        mediaPlayer?.release()
        mediaPlayer = null
        
        // The previous M4A is now stale, we will generate a new one from the updated PCM
        recordedFile = null
        
        // Start recording again, appending to the existing PCM file
        val file = audioRecorder.start(append = true)
        if (file != null) {
            _state.value = VoiceState.RECORDING
            startTimer()
        } else {
             _state.value = VoiceState.IDLE
        }
    }

    fun stopRecordingForReview() {
        if (_state.value != VoiceState.RECORDING && _state.value != VoiceState.PAUSED) return
        if (isFinalizing) return

        timerJob?.cancel()
        val finalDuration = _duration.value
        _state.value = VoiceState.PAUSED
        isFinalizing = true
        managerScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    audioRecorder.stop()
                }
                if (file != null && finalDuration > 500) {
                    recordedFile = file
                    _state.value = VoiceState.REVIEWING
                    preparePreview(file)
                } else {
                    reset()
                }
            } finally {
                isFinalizing = false
            }
        }
    }
    
    // Immediate stop and return file (drag to send)
    suspend fun stopAndGetFile(): Pair<File?, Long> {
        if (isFinalizing) return Pair(null, _duration.value)
        isFinalizing = true
        timerJob?.cancel()
        val finalDuration = _duration.value
        _state.value = VoiceState.PAUSED
        return try {
            val file = withContext(Dispatchers.IO) {
                audioRecorder.stop()
            }
            reset()
            Pair(file, finalDuration)
        } finally {
            isFinalizing = false
        }
    }

    fun cancelRecording() {
        audioRecorder.cancel()
        reset()
    }

    // --- Preview Control ---
    
    private fun preparePreview(file: File) {
        try {
             mediaPlayer?.release()
             mediaPlayer = null
             isPreviewPrepared = false
             
             mediaPlayer = MediaPlayer().apply {
                 setAudioAttributes(
                     android.media.AudioAttributes.Builder()
                         .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                         .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                         .build()
                 )
                 setDataSource(file.absolutePath)
                 setOnPreparedListener { preparedPlayer ->
                     isPreviewPrepared = true
                     _playbackDurationLabel.value = formatDuration(preparedPlayer.duration.toLong())
                 }
                 setOnCompletionListener { 
                     _playbackProgress.value = 1f 
                     _isDraftPlaying.value = false
                 }
                 setOnErrorListener { _, _, _ ->
                     isPreviewPrepared = false
                     _isDraftPlaying.value = false
                     true
                 }
                 prepareAsync()
             }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playPreview() {
        if (_state.value != VoiceState.REVIEWING) return
        
        mediaPlayer?.takeIf { isPreviewPrepared }?.let { mp ->
            if (!mp.isPlaying) {
                if (mp.currentPosition >= mp.duration) {
                     // If finished, restart but check if duration > 0
                     mp.seekTo(0)
                     _playbackProgress.value = 0f
                }
                mp.start()
                _isDraftPlaying.value = true
                startPlaybackTracker()
            }
        }
    }
    
    fun pausePreview() {
        if (_state.value != VoiceState.REVIEWING) return
        
        mediaPlayer?.let { mp ->
            if (mp.isPlaying) {
                mp.pause()
                _isDraftPlaying.value = false
            }
        }
        playbackJob?.cancel()
    }
    
    fun seekPreview(fraction: Float) {
        mediaPlayer?.let { mp ->
             val pos = (mp.duration * fraction).toInt()
             mp.seekTo(pos)
             _playbackProgress.value = fraction
        }
    }
    
    fun isPreviewPlaying(): Boolean {
        return mediaPlayer?.isPlaying == true
    }

    // --- Helpers ---

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = managerScope.launch {
            val startTime = System.currentTimeMillis() - _duration.value
            while (_state.value == VoiceState.RECORDING) {
                _duration.value = System.currentTimeMillis() - startTime
                delay(100)
            }
        }
    }
    
    private fun startPlaybackTracker() {
        playbackJob?.cancel()
        playbackJob = managerScope.launch {
             while (mediaPlayer?.isPlaying == true) {
                 val mp = mediaPlayer ?: break
                 if (mp.duration > 0) {
                    _playbackProgress.value = mp.currentPosition.toFloat() / mp.duration.toFloat()
                 }
                 delay(50)
             }
             if (mediaPlayer?.isPlaying == false && _playbackProgress.value < 1f) {
                 // Update one last time for pause state if needed
             }
        }
    }

    fun reset() {
        timerJob?.cancel()
        playbackJob?.cancel()
        audioRecorder.cancel() // Ensure underlying is clean
        
        mediaPlayer?.release()
        mediaPlayer = null
        isPreviewPrepared = false
        
        recordedFile = null
        _duration.value = 0L
        _playbackProgress.value = 0f
        _isDraftPlaying.value = false
        _state.value = VoiceState.IDLE
    }
    
    fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000) / 60
        return "%d:%02d".format(minutes, seconds)
    }
    
    // For final send in Review mode
    fun getCurrentFile(): File? = recordedFile

    // Compatibility methods
    fun stopRecording() {
        stopRecordingForReview()
    }

    fun getRecordedFile(): File? {
        return recordedFile
    }

    fun clearRecording() {
        reset()
    }
}
