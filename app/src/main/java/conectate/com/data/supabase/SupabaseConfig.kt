package conectate.com.data.supabase

import android.util.Log
import conectate.com.BuildConfig
import conectate.com.data.localfallback.RuntimeBackendEndpoint
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
import conectate.com.core.network.AutomaticRetryPolicy
import conectate.com.core.network.PublicBackendDns
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.*
import io.github.jan.supabase.annotations.SupabaseInternal
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseConfig @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
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
    private val rawFallbackApiUrl: String
        get() = RuntimeBackendEndpoint.baseUrl
    private val hasSupabaseBackend: Boolean =
        rawSupabaseUrl.startsWith("http", ignoreCase = true) && rawSupabaseAnonKey.isNotBlank()
    private val hasFallbackBackend: Boolean
        get() = RuntimeBackendEndpoint.isConfigured
    /** Ubuntu/local API wins whenever configured, even if legacy Supabase keys remain in secrets.properties. */
    val isLocalFallbackPrimary: Boolean get() = hasFallbackBackend
    val supabaseUrl: String get() = if (isLocalFallbackPrimary) rawFallbackApiUrl else rawSupabaseUrl
    val supabaseAnonKey: String get() = if (isLocalFallbackPrimary) LOCAL_FALLBACK_ANON_KEY else rawSupabaseAnonKey
    /** A usable backend exists. Ubuntu is the production backend whenever it is configured. */
    val isConfigured: Boolean
        get() = hasFallbackBackend || hasSupabaseBackend

    // URLs específicas para diferentes servicios
    val authUrl: String get() = "$supabaseUrl/auth/v1"
    val storageUrl: String get() = "$supabaseUrl/storage/v1"

    val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    val httpClient = HttpClient(OkHttp) {
        engine {
            config { dns(PublicBackendDns) }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 25_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 25_000
        }
        install(HttpRequestRetry) {
            maxRetries = 2
            exponentialDelay(base = 300.0, maxDelayMs = 4_000)
            retryIf { request, response ->
                AutomaticRetryPolicy.mayRetryResponse(
                    method = request.method.value,
                    statusCode = response.status.value,
                )
            }
            retryOnExceptionIf { request, cause ->
                AutomaticRetryPolicy.mayRetry(request.method.value) &&
                    (cause is IOException || cause is HttpRequestTimeoutException)
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

    // Legacy SDK facade kept temporarily for repositories that still use its PostgREST DSL.
    // With Ubuntu primary it points only to Ubuntu's compatibility routes. Auth and Realtime
    // operations are routed through AuthService/Socket.IO and remain disabled in this facade.
    @Volatile
    private var compatibilityClient: io.github.jan.supabase.SupabaseClient? = null

    @Volatile
    private var compatibilityClientUrl: String = ""

    /**
     * Compatibility facade for legacy PostgREST call sites.
     *
     * RuntimeBackendEndpoint can change after DNS discovery, so this cannot be a one-shot lazy
     * value. Rebuilding on URL change keeps every repository on the current Ubuntu endpoint.
     */
    val client: io.github.jan.supabase.SupabaseClient
        get() {
            val targetUrl = when {
                isLocalFallbackPrimary -> supabaseUrl
                hasSupabaseBackend -> rawSupabaseUrl
                else -> DISABLED_SUPABASE_URL
            }
            compatibilityClient?.takeIf { compatibilityClientUrl == targetUrl }?.let { return it }

            return synchronized(this) {
                compatibilityClient?.takeIf { compatibilityClientUrl == targetUrl } ?: run {
                    val targetKey = when {
                        isLocalFallbackPrimary -> LOCAL_FALLBACK_ANON_KEY
                        hasSupabaseBackend -> rawSupabaseAnonKey
                        else -> DISABLED_SUPABASE_KEY
                    }
                    val startAt = System.currentTimeMillis()
                    createSupabaseClientInternal(targetUrl, targetKey).also { created ->
                        compatibilityClient = created
                        compatibilityClientUrl = targetUrl
                        Log.i(
                            "SupabaseConfig",
                            "Compatibility client initialized for $targetUrl in " +
                                "${System.currentTimeMillis() - startAt}ms"
                        )
                    }
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

    @OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
    private fun createSupabaseClientInternal(
        safeUrl: String,
        safeKey: String
    ): io.github.jan.supabase.SupabaseClient {
        return io.github.jan.supabase.createSupabaseClient(
            supabaseUrl = safeUrl,
            supabaseKey = safeKey
        ) {
            httpEngine = OkHttp.create { config { dns(PublicBackendDns) } }
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
                if (isLocalFallbackPrimary) {
                    alwaysAutoRefresh = false
                    autoLoadFromStorage = false
                    autoSaveToStorage = false
                    // Ubuntu owns authentication and token refresh. The legacy SDK facade is
                    // retained only for PostgREST compatibility, so registering Android
                    // lifecycle callbacks is unnecessary. More importantly, this client can
                    // legitimately be created by a repository on Dispatchers.IO; Lifecycle's
                    // addObserver is main-thread-only and would otherwise abort that sync.
                    enableLifecycleCallbacks = false
                }
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
        blockUntilSubscribed: Boolean = true
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


