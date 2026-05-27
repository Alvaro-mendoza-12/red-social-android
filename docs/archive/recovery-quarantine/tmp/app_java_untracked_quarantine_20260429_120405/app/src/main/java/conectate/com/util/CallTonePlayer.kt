package conectate.com.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import conectate.com.core.concurrency.newSafeScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
class CallTonePlayer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = newSafeScope("CallTonePlayer", Dispatchers.Default)
    private val lock = Any()
    private val toneGenerator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
    }

    @Volatile
    private var ringbackJob: Job? = null

    fun startRingback() {
        if (!shouldPlay() || ringbackJob?.isActive == true) return

        ringbackJob = scope.launch {
            while (isActive) {
                synchronized(lock) {
                    runCatching {
                        toneGenerator.startTone(ToneGenerator.TONE_SUP_RINGTONE, 1400)
                    }
                }
                delay(1700)
            }
        }
    }

    fun stopRingback() {
        ringbackJob?.cancel()
        ringbackJob = null
        synchronized(lock) {
            runCatching { toneGenerator.stopTone() }
        }
    }

    private fun shouldPlay(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }
}


