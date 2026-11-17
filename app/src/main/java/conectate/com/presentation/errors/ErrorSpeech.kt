package conectate.com.presentation.errors

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import conectate.com.core.result.ErrorCharacter
import conectate.com.core.result.NovvinProblem
import java.util.Locale

internal class ErrorSpeech(private val context: Context) {
    var active by mutableStateOf(false)
        private set
    var unavailable by mutableStateOf(false)
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var sequence = 0
    private var pending: NovvinProblem? = null

    fun listen(problem: NovvinProblem) {
        stop()
        unavailable = false
        active = true
        pending = problem
        if (ready) {
            playPending()
        } else if (engine == null) {
            engine = TextToSpeech(context) { status -> handler.post { initialize(status) } }
        }
    }

    private fun initialize(status: Int) {
        if (closed) return
        val speech = engine ?: return
        if (status != TextToSpeech.SUCCESS ||
            speech.setLanguage(Locale.forLanguageTag("es")) < TextToSpeech.LANG_AVAILABLE
        ) {
            closeEngine()
            pending = null
            unavailable = active
            active = false
            return
        }
        speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId, false)
            @Deprecated("Required by the platform listener")
            override fun onError(utteranceId: String?) = finish(utteranceId, true)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId, true)
        })
        ready = true
        playPending()
    }

    private fun finish(id: String?, failed: Boolean) {
        handler.post {
            if (!closed && id == sequence.toString()) {
                active = false
                unavailable = failed
            }
        }
    }

    private fun playPending() {
        val problem = pending ?: return
        val speech = engine ?: return
        pending = null
        val (pitch, rate) = when (problem.character) {
            ErrorCharacter.Confused -> 1.13f to 0.94f
            ErrorCharacter.Guardian -> 0.93f to 0.92f
            ErrorCharacter.Protector -> 0.98f to 1.01f
            ErrorCharacter.Detective -> 1.14f to 1.03f
            ErrorCharacter.Overloaded -> 0.86f to 1.08f
            ErrorCharacter.Sleeper -> 0.95f to 0.79f
        }
        speech.setPitch(pitch)
        speech.setSpeechRate(rate)
        if (speech.speak(problem.narration, TextToSpeech.QUEUE_FLUSH, null, sequence.toString()) == TextToSpeech.ERROR) {
            active = false
            unavailable = true
        }
    }

    fun stop() {
        sequence++
        pending = null
        if (active) engine?.stop()
        active = false
        unavailable = false
    }

    fun close() {
        closed = true
        stop()
        closeEngine()
    }

    private fun closeEngine() {
        ready = false
        engine?.shutdown()
        engine = null
    }
}
