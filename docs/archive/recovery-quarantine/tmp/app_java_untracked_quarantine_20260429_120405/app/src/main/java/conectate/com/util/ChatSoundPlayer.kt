package conectate.com.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import conectate.com.data.local.prefs.MessagesPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatSoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messagesPreferences: MessagesPreferences
) {
    companion object {
        private const val SEND_DEBOUNCE_MS = 120L
        private const val RECEIVE_DEBOUNCE_MS = 180L
        private const val TONE_VOLUME = 78
    }

    private val lock = Any()
    private val toneGenerator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME)
    }

    @Volatile
    private var lastSendAtMs = 0L

    @Volatile
    private var lastReceiveAtMs = 0L

    fun playSend() {
        play(
            tone = ToneGenerator.TONE_PROP_ACK,
            durationMs = 110,
            debounceMs = SEND_DEBOUNCE_MS,
            isSend = true
        )
    }

    fun playReceive() {
        play(
            tone = ToneGenerator.TONE_PROP_PROMPT,
            durationMs = 150,
            debounceMs = RECEIVE_DEBOUNCE_MS,
            isSend = false
        )
    }

    private fun play(
        tone: Int,
        durationMs: Int,
        debounceMs: Long,
        isSend: Boolean
    ) {
        if (!shouldPlay()) return

        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val lastPlayedAt = if (isSend) lastSendAtMs else lastReceiveAtMs
            if (now - lastPlayedAt < debounceMs) return

            if (isSend) {
                lastSendAtMs = now
            } else {
                lastReceiveAtMs = now
            }

            runCatching {
                toneGenerator.startTone(tone, durationMs)
            }
        }
    }

    private fun shouldPlay(): Boolean {
        if (!messagesPreferences.chatSoundsEnabled.value) return false
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL
    }
}




