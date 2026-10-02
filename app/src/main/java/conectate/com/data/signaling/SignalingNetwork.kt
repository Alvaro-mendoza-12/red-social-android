package conectate.com.data.signaling

import conectate.com.core.network.PublicBackendDns
import okhttp3.OkHttpClient
import okhttp3.Dispatcher
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object SignalingNetwork {
    // Long-polling must be allowed to wait beyond the server's ping interval.
    // TURN derives a bounded request client from this same connection pool.
    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(PublicBackendDns)
        // Each polling namespace needs a receive GET and a send POST. Leave
        // capacity for call/voice/LIVE and a connection being handed over.
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val preparationClient = client.newBuilder()
        .callTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private var preparedBaseUrl = ""
    private var lastPreparationMs = 0L

    /** Prepares DNS/TLS without joining a call or activating any microphone. */
    fun prepare(rawBaseUrl: String) {
        val baseUrl = rawBaseUrl.trim().trimEnd('/')
        if (!baseUrl.startsWith("https://")) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (preparedBaseUrl == baseUrl && now - lastPreparationMs in 0..120_000L) return
            preparedBaseUrl = baseUrl
            lastPreparationMs = now
        }
        try {
            preparationClient.newCall(Request.Builder().url("$baseUrl/ready").head().build())
                .execute().use { /* The warm-up is optional; calling remains independently retryable. */ }
        } catch (_: IOException) {
            // Offline users can still read a conversation; no error UI for preparation.
        } catch (_: IllegalArgumentException) {
            // An invalid endpoint is handled by the normal endpoint recovery path.
        }
    }
}
