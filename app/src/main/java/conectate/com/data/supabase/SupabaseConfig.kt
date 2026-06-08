package conectate.com.data.supabase

import android.os.Looper
import android.util.Log
import conectate.com.BuildConfig
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.*
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.*
import io.github.jan.supabase.annotations.SupabaseInternal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseConfig @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {
    private val realtimeMutex = Mutex()
    private val realtimeProbeMutex = Mutex()

    @Volatile
    private var realtimeDisabledUntilMs: Long = 0L

    @Volatile
    private var lastRealtimeProbeAtMs: Long = 0L

    @Volatile
    private var lastRealtimeRestrictionMessage: String? = null

    @Volatile
    private var lastRealtimeRestrictionLogMs: Long = 0L

    private val rawSupabaseUrl: String = BuildConfig.SUPABASE_URL.trim()
    private val rawSupabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY.trim()
    private val rawFallbackApiUrl: String = BuildConfig.FALLBACK_API_URL.trim().replace(Regex("/+$"), "")
    private val hasSupabaseBackend: Boolean =
        rawSupabaseUrl.startsWith("http", ignoreCase = true) && rawSupabaseAnonKey.isNotBlank()
    private val hasFallbackBackend: Boolean =
        rawFallbackApiUrl.startsWith("http", ignoreCase = true)
    /** Ubuntu/local API is primary only when configured and Supabase is not. */
    val isLocalFallbackPrimary: Boolean = hasFallbackBackend && !hasSupabaseBackend
    val supabaseUrl: String = if (isLocalFallbackPrimary) rawFallbackApiUrl else rawSupabaseUrl
    val supabaseAnonKey: String = if (isLocalFallbackPrimary) LOCAL_FALLBACK_ANON_KEY else rawSupabaseAnonKey
    val isConfigured: Boolean = hasSupabaseBackend

    // URLs específicas para diferentes servicios
    val authUrl: String = "$supabaseUrl/auth/v1"
    val storageUrl: String = "$supabaseUrl/storage/v1"

    val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 25_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 25_000
        }
        install(HttpRequestRetry) {
            maxRetries = 2
            exponentialDelay(base = 300.0, maxDelayMs = 4_000)
            retryIf { _, response ->
                response.status.value == 429 || response.status.value >= 500
            }
            retryOnExceptionIf { _, cause ->
                cause is IOException || cause is HttpRequestTimeoutException
            }
        }
        install(ContentNegotiation) {
            json(this@SupabaseConfig.json)
        }
        install(Logging) {
            logger = Logger.DEFAULT
            level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
        }
        expectSuccess = false
    }

    // Official Supabase Client for Realtime
    // IMPORTANT: Initialized lazily to avoid blocking the Main thread during app startup.
    // The Supabase Auth plugin registers lifecycle callbacks and must be created on Main.
    // CRITICAL: When Ubuntu is the primary backend, do NOT initialize Supabase client at all.
    @OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
    val client: io.github.jan.supabase.SupabaseClient by lazy {
        val startAt = System.currentTimeMillis()

        // Si Ubuntu es el backend principal, no inicializar Supabase
        if (isLocalFallbackPrimary) {
            Log.w("SupabaseConfig", "⚠️ Ubuntu is primary backend - Skipping Supabase client initialization")
            createSupabaseClientOnMain(DISABLED_SUPABASE_URL, DISABLED_SUPABASE_KEY)
        } else {
            try {
                val safeUrl = if (isConfigured) supabaseUrl else DISABLED_SUPABASE_URL
                val safeKey = if (isConfigured) supabaseAnonKey else DISABLED_SUPABASE_KEY

                createSupabaseClientOnMain(safeUrl, safeKey).also {
                    val endAt = System.currentTimeMillis()
                    Log.d("SupabaseConfig", "✅ Supabase Client initialized in ${endAt - startAt}ms")
                }
            } catch (e: Exception) {
                Log.e("SupabaseConfig", "❌ CRITICAL: Failed to initialize Supabase Client", e)
                createSupabaseClientOnMain(DISABLED_SUPABASE_URL, DISABLED_SUPABASE_KEY)
            }
        }
    }

    init {
        if (!isConfigured) {
            Log.w("SupabaseConfig", "Supabase URL or Anon Key not configured")
        }
    }

    private fun Throwable.isAlreadyConnectedRealtimeError(): Boolean {
        return message?.contains("Websocket already connected", ignoreCase = true) == true
    }

    private fun Throwable.isRealtimeHandshakeSerializationError(): Boolean {
        val raw = stackTraceToString()
        return raw.contains("DefaultClientWebSocketSession") &&
            raw.contains("SerializationException") &&
            raw.contains("ContentNegotiation")
    }

    private fun logRealtimePaused(reason: String, message: String?) {
        val now = System.currentTimeMillis()
        if (now - lastRealtimeRestrictionLogMs < REALTIME_RESTRICTION_LOG_INTERVAL_MS) return
        lastRealtimeRestrictionLogMs = now
        Log.w(
            "SupabaseConfig",
            "Realtime paused [$reason]: ${message ?: "Supabase service temporarily unavailable"}"
        )
    }

    private fun pauseRealtime(reason: String, message: String?) {
        lastRealtimeRestrictionMessage = message
        realtimeDisabledUntilMs = System.currentTimeMillis() + REALTIME_RESTRICTION_PAUSE_MS
        logRealtimePaused(reason, message)
    }

    fun reportServiceRestrictionIfPresent(reason: String, throwable: Throwable): Boolean {
        val restrictionMessage = mapSupabaseServiceRestrictionMessage(
            rawMessage = throwable.message ?: throwable.stackTraceToString()
        )
        if (restrictionMessage != null) {
            pauseRealtime(reason, restrictionMessage)
            return true
        }
        return false
    }

    private suspend fun shouldSkipRealtimeConnection(reason: String): Boolean {
        if (isLocalFallbackPrimary || !isConfigured) {
            logRealtimePaused(reason, "Realtime de Supabase desactivado porque Ubuntu/Fallback API es el backend principal.")
            return true
        }

        val now = System.currentTimeMillis()
        if (now < realtimeDisabledUntilMs) {
            logRealtimePaused(reason, lastRealtimeRestrictionMessage)
            return true
        }

        if (supabaseUrl.isBlank() || supabaseAnonKey.isBlank()) return false
        if (now - lastRealtimeProbeAtMs < REALTIME_RESTRICTION_PROBE_INTERVAL_MS) return false

        return realtimeProbeMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (lockedNow < realtimeDisabledUntilMs) {
                logRealtimePaused(reason, lastRealtimeRestrictionMessage)
                return@withLock true
            }
            if (lockedNow - lastRealtimeProbeAtMs < REALTIME_RESTRICTION_PROBE_INTERVAL_MS) {
                return@withLock false
            }

            lastRealtimeProbeAtMs = lockedNow
            val restrictionMessage = runCatching {
                val response = httpClient.get("${supabaseUrl.removeSuffix("/")}/rest/v1/profiles?select=id&limit=1") {
                    header("apikey", supabaseAnonKey)
                    header(HttpHeaders.Authorization, "Bearer $supabaseAnonKey")
                }
                val body = response.bodyAsText()
                mapSupabaseServiceRestrictionMessage(body)
                    ?: if (response.status.value == 402) {
                        mapSupabaseServiceRestrictionMessage("402 restricted quota")
                    } else {
                        null
                    }
            }.getOrNull()

            if (restrictionMessage != null) {
                pauseRealtime(reason, restrictionMessage)
                true
            } else {
                false
            }
        }
    }

    private fun createSupabaseClientOnMain(
        safeUrl: String,
        safeKey: String
    ): io.github.jan.supabase.SupabaseClient {
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            createSupabaseClientInternal(safeUrl, safeKey)
        } else {
            runBlocking(Dispatchers.Main.immediate) {
                createSupabaseClientInternal(safeUrl, safeKey)
            }
        }
    }

    @OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
    private fun createSupabaseClientInternal(
        safeUrl: String,
        safeKey: String
    ): io.github.jan.supabase.SupabaseClient {
        return io.github.jan.supabase.createSupabaseClient(
            supabaseUrl = safeUrl,
            supabaseKey = safeKey
        ) {
            httpConfig {
                install(HttpTimeout) {
                    requestTimeoutMillis = 30000
                    connectTimeoutMillis = 30000
                    socketTimeoutMillis = 30000
                }
            }
            defaultSerializer = io.github.jan.supabase.serializer.KotlinXSerializer(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
                encodeDefaults = true
            })
            install(io.github.jan.supabase.gotrue.Auth) {
                scheme = "conectate"
                host = "recovery"
            }
            install(io.github.jan.supabase.realtime.Realtime) {
                connectOnSubscribe = false
            }
            install(io.github.jan.supabase.postgrest.Postgrest)
            install(io.github.jan.supabase.storage.Storage)
        }
    }

    private suspend fun connectRealtimeLocked(reason: String) {
        if (shouldSkipRealtimeConnection(reason)) return

        try {
            client.realtime.connect()
            Log.d("SupabaseConfig", "Realtime connect OK [$reason]")
        } catch (e: IllegalStateException) {
            if (e.isAlreadyConnectedRealtimeError()) {
                Log.d("SupabaseConfig", "Realtime already connected [$reason]")
            } else {
                throw e
            }
        } catch (e: Exception) {
            if (e.isRealtimeHandshakeSerializationError()) {
                pauseRealtime(reason, "Realtime no pudo abrir WebSocket; el backend puede estar devolviendo una respuesta REST en lugar del upgrade WebSocket.")
                return
            }
            throw e
        }
    }

    suspend fun ensureRealtimeConnected(reason: String = "unknown") {
        realtimeMutex.withLock {
            connectRealtimeLocked(reason)
        }
    }

    suspend fun subscribeRealtimeChannel(
        channel: RealtimeChannel,
        reason: String,
        blockUntilSubscribed: Boolean = false
    ) {
        if (shouldSkipRealtimeConnection(reason)) return

        realtimeMutex.withLock {
            if (shouldSkipRealtimeConnection(reason)) return@withLock

            try {
                if (client.realtime.status.value != io.github.jan.supabase.realtime.Realtime.Status.CONNECTED) {
                    connectRealtimeLocked(reason)
                    if (client.realtime.status.value != io.github.jan.supabase.realtime.Realtime.Status.CONNECTED) {
                        return@withLock
                    }
                }
                channel.subscribe(blockUntilSubscribed = blockUntilSubscribed)
                Log.d("SupabaseConfig", "Realtime subscribe OK [$reason]")
            } catch (e: IllegalStateException) {
                if (!e.isAlreadyConnectedRealtimeError()) {
                    throw e
                }

                Log.w(
                    "SupabaseConfig",
                    "Realtime subscribe race detected [$reason], retrying once without forcing reconnect"
                )
                delay(150)
                channel.subscribe(blockUntilSubscribed = blockUntilSubscribed)
                Log.d("SupabaseConfig", "Realtime subscribe retry OK [$reason]")
            } catch (e: Exception) {
                if (e.isRealtimeHandshakeSerializationError()) {
                    pauseRealtime(reason, "Realtime no pudo abrir WebSocket; el backend puede estar devolviendo una respuesta REST en lugar del upgrade WebSocket.")
                    return@withLock
                }
                throw e
            }
        }
    }

    private companion object {
        private const val DISABLED_SUPABASE_URL = "https://disabled.supabase.invalid"
        private const val DISABLED_SUPABASE_KEY = "disabled-anon-key"
        private const val LOCAL_FALLBACK_ANON_KEY = "local-fallback-anon-key"
        private const val REALTIME_RESTRICTION_PAUSE_MS = 5 * 60 * 1_000L
        private const val REALTIME_RESTRICTION_PROBE_INTERVAL_MS = 60 * 1_000L
        private const val REALTIME_RESTRICTION_LOG_INTERVAL_MS = 30 * 1_000L
    }
}


