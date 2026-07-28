package conectate.com.data.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Session-scoped speech recognition for opt-in voice captions.
 *
 * Audio is owned by Android's configured recognition service. Novvin only
 * receives the partial/final text callbacks; it never records or uploads raw
 * audio from this class.
 */
class VoiceCaptionRecognizer(context: Context) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onUnavailable(message: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var listener: Listener? = null
    private var languageTag: String = Locale.getDefault().toLanguageTag()
    private var running = false
    private var restartScheduled = false
    private var transientErrorCount = 0

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start(languageTag: String?, listener: Listener) {
        this.listener = listener
        this.languageTag = languageTag
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: Locale.getDefault().toLanguageTag()
        running = true
        transientErrorCount = 0
        mainHandler.post {
            if (!running) return@post
            if (!isAvailable()) {
                fail("Este dispositivo no tiene disponible el reconocimiento de voz.")
                return@post
            }
            ensureRecognizer()
            startListeningNow()
        }
    }

    fun stop() {
        running = false
        restartScheduled = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            listener = null
        }
    }

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }.also { recognizer ->
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    resultText(partialResults)?.let { listener?.onPartial(it) }
                }

                override fun onResults(results: Bundle?) {
                    transientErrorCount = 0
                    resultText(results)?.let { listener?.onFinal(it) }
                    scheduleRestart(180L)
                }

                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleRestart(250L)

                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleRestart(850L)

                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> {
                            transientErrorCount += 1
                            if (transientErrorCount <= 4) {
                                scheduleRestart((400L * transientErrorCount).coerceAtMost(1_600L))
                            } else {
                                fail("No se pudo continuar con los subtítulos. Revisa tu conexión.")
                            }
                        }

                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            fail("Concede permiso al micrófono para activar los subtítulos.")

                        SpeechRecognizer.ERROR_AUDIO ->
                            fail("Este dispositivo no permite subtitular mientras otra app usa el micrófono.")

                        else -> fail("Los subtítulos se detuvieron. Puedes volver a activarlos.")
                    }
                }
            })
        }
    }

    private fun startListeningNow() {
        if (!running) return
        restartScheduled = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching {
            speechRecognizer?.startListening(intent)
        }.onFailure {
            fail("No se pudieron iniciar los subtítulos en este dispositivo.")
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running || restartScheduled) return
        restartScheduled = true
        mainHandler.postDelayed({ startListeningNow() }, delayMs)
    }

    private fun resultText(bundle: Bundle?): String? {
        return bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(500)
            ?.takeIf(String::isNotBlank)
    }

    private fun fail(message: String) {
        if (!running) return
        running = false
        restartScheduled = false
        listener?.onUnavailable(message)
    }
}
