package conectate.com.data.supabase

import android.util.Log
import conectate.com.BuildConfig
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.*
import io.ktor.serialization.kotlinx.json.*
import io.github.jan.supabase.annotations.SupabaseInternal
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseConfig @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {

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
            level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE
        }
        expectSuccess = false
    }

    // Official Supabase Client for Realtime
    // IMPORTANT: Uses CIO engine which supports WebSockets for Realtime
    @OptIn(SupabaseInternal::class)
    val client = try {
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
        }
    } catch (e: Exception) {
        Log.e("SupabaseConfig", "CRITICAL: Failed to initialize Supabase Client", e)
        // Return a dummy safe client to prevent DI crash, even if calls will fail later
        @OptIn(SupabaseInternal::class)
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

    init {
        if (supabaseUrl.isBlank() || supabaseAnonKey.isBlank()) {
            Log.w("SupabaseConfig", "Supabase URL or Anon Key not configured")
        }
    }
}
