package conectate.com.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
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
    @param:ApplicationContext private val context: Context
) {
    private val scope = newSafeScope("CallTonePlayer", Dispatchers.Default)
    private val lock = Any()
    private val toneGenerator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80)
    }

    @Volatile
    private var ringbackJob: Job? = null

    @Volatile
    private var incomingRingtoneJob: Job? = null

    private var incomingRingtone: Ringtone? = null

    fun startRingback() {
        if (!shouldPlay() || ringbackJob?.isActive == true) return
        stopIncomingRingtone()

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

    fun startIncomingRingtone() {
        if (!shouldPlay() || incomingRingtoneJob?.isActive == true) return
        stopRingback()

        incomingRingtoneJob = scope.launch {
            val ringtone = synchronized(lock) {
                incomingRingtone ?: createIncomingRingtone().also { incomingRingtone = it }
            } ?: return@launch

            while (isActive) {
                synchronized(lock) {
                    runCatching {
                        if (!ringtone.isPlaying) ringtone.play()
                    }
                }
                // Android 9+ loops natively. On older devices this also restarts
                // non-looping manufacturer ringtones without busy-waiting.
                delay(3_000L)
            }
        }
    }

    fun stopIncomingRingtone() {
        incomingRingtoneJob?.cancel()
        incomingRingtoneJob = null
        synchronized(lock) {
            runCatching { incomingRingtone?.stop() }
        }
    }

    fun stopRingback() {
        ringbackJob?.cancel()
        ringbackJob = null
        synchronized(lock) {
            runCatching { toneGenerator.stopTone() }
        }
    }

    fun stopAll() {
        stopRingback()
        stopIncomingRingtone()
    }

    private fun createIncomingRingtone(): Ringtone? {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        return RingtoneManager.getRingtone(context, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                isLooping = true
            }
        }
    }

    private fun shouldPlay(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }
}


