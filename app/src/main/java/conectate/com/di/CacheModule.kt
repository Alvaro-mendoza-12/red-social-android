package conectate.com.di

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import conectate.com.BuildConfig
import conectate.com.data.cache.UpstashCacheService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CacheModule {

    @Provides
    @Singleton
    fun provideFirebaseCrashlytics(): FirebaseCrashlytics = FirebaseCrashlytics.getInstance()

    @Provides
    @Singleton
    fun provideKtorHttpClient(): HttpClient {
        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                })
            }
            install(Logging) {
                level = if (BuildConfig.DEBUG) LogLevel.ALL else LogLevel.NONE
            }
        }
    }

    @Provides
    @Singleton
    fun provideUpstashCacheService(
        httpClient: HttpClient,
        crashlytics: FirebaseCrashlytics
    ): UpstashCacheService {
        // Validar configuración
        if (BuildConfig.UPSTASH_REST_URL.isBlank() || BuildConfig.UPSTASH_TOKEN.isBlank()) {
            Log.w("CacheModule", "Upstash no configurado. Cache deshabilitado.")
            // Retornar dummy service? Por ahora lanzamos excepción clara
            // En producción, se puede usar NoOpCacheService
        }
        return UpstashCacheService(httpClient)
    }
}
