package conectate.com.data.supabase

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
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {
    private val realtimeMutex = Mutex()

    val supabaseUrl: String = BuildConfig.SUPABASE_URL.ifEmpty { "" }
    val supabaseAnonKey: String = BuildConfig.SUPABASE_ANON_KEY.ifEmpty { "" }

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
    // The first access usually occurs in AuthService on a background dispatcher.
    @OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
    val client: io.github.jan.supabase.SupabaseClient by lazy {
        val startAt = System.currentTimeMillis()
        try {
            // Validation to prevent crash on illegal argument
            val safeUrl = if (supabaseUrl.isNotBlank() && supabaseUrl.startsWith("http")) supabaseUrl else "https://placeholder.supabase.co"
            val safeKey = if (supabaseAnonKey.isNotBlank()) supabaseAnonKey else "placeholder-key"
            
            io.github.jan.supabase.createSupabaseClient(
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
                install(io.github.jan.supabase.realtime.Realtime)
                install(io.github.jan.supabase.postgrest.Postgrest)
                install(io.github.jan.supabase.storage.Storage)
            }.also {
                val endAt = System.currentTimeMillis()
                Log.d("SupabaseConfig", "✅ Supabase Client initialized in ${endAt - startAt}ms")
            }
        } catch (e: Exception) {
            Log.e("SupabaseConfig", "❌ CRITICAL: Failed to initialize Supabase Client", e)
            // Return a dummy safe client to prevent DI crash, even if calls will fail later
            io.github.jan.supabase.createSupabaseClient(
                supabaseUrl = "https://placeholder.supabase.co",
                supabaseKey = "placeholder"
            ) {
                 defaultSerializer = io.github.jan.supabase.serializer.KotlinXSerializer(Json { ignoreUnknownKeys = true })
                 install(io.github.jan.supabase.gotrue.Auth) {
                    scheme = "conectate"
                    host = "recovery"
                }
                install(io.github.jan.supabase.realtime.Realtime)
                install(io.github.jan.supabase.postgrest.Postgrest)
                install(io.github.jan.supabase.storage.Storage)
            }
        }
    }

    init {
        if (supabaseUrl.isBlank() || supabaseAnonKey.isBlank()) {
            Log.w("SupabaseConfig", "Supabase URL or Anon Key not configured")
        }
    }

    private fun Throwable.isAlreadyConnectedRealtimeError(): Boolean {
        return message?.contains("Websocket already connected", ignoreCase = true) == true
    }

    private suspend fun connectRealtimeLocked(reason: String) {
        try {
            client.realtime.connect()
            Log.d("SupabaseConfig", "Realtime connect OK [$reason]")
        } catch (e: IllegalStateException) {
            if (e.isAlreadyConnectedRealtimeError()) {
                Log.d("SupabaseConfig", "Realtime already connected [$reason]")
            } else {
                throw e
            }
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
        realtimeMutex.withLock {
            try {
                // Let Supabase subscribe manage the websocket lifecycle to avoid double-connect races.
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
            }
        }
    }
}


