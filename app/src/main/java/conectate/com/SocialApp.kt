package conectate.com

import android.app.Application
import android.content.ComponentCallbacks2
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.Coil
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import androidx.work.Configuration
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.crash.CrashReporter
import conectate.com.core.logging.AppLogger
import conectate.com.core.observability.AppMetrics
import conectate.com.core.observability.MetricAlertSeverity
import conectate.com.core.stability.RuntimeStabilityMonitor
import conectate.com.core.production.ProductionReadyOptimizer
import conectate.com.data.observability.SupabaseTelemetryExporter
import conectate.com.data.sync.SyncManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import androidx.hilt.work.HiltWorkerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import conectate.com.util.AppDeviceTier
import conectate.com.util.AppPerformanceProfiles

@HiltAndroidApp
class SocialApp : Application(), ImageLoaderFactory, Configuration.Provider {
    
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var crashReporter: CrashReporter
    @Inject lateinit var logger: AppLogger
    @Inject lateinit var appMetrics: AppMetrics
    @Inject lateinit var telemetryExporter: SupabaseTelemetryExporter
    @Inject lateinit var syncManager: SyncManager
    @Inject lateinit var runtimeStabilityMonitor: RuntimeStabilityMonitor
    
    // 🔥 PRODUCTION OPTIMIZER
    private lateinit var productionOptimizer: ProductionReadyOptimizer

    override val workManagerConfiguration: Configuration
        get() = try {
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()
        } catch (e: Exception) {
            Configuration.Builder().build()
        }

    private val appScope = newSafeScope("SocialApp", Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        
        // 🔥 INICIALIZAR OPTIMIZADOR DE PRODUCCIÓN
        productionOptimizer = ProductionReadyOptimizer(this)
        productionOptimizer.initializeForProduction()
        
        // Initialize Coil with optimized settings
        Coil.setImageLoader(newImageLoader())
        
        // Initialize crash reporting
        Firebase.crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
        Firebase.crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
        Firebase.crashlytics.setCustomKey("version_code", BuildConfig.VERSION_CODE)

        if (BuildConfig.DEBUG) {
            android.util.Log.d("SocialApp", "APP_START: Conectate Social App initializing...")
        }
        
        // Setup unhandled exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Not using logger here to avoid potential DI dependencies crashing if they are corrupted
                android.util.Log.e("SocialApp", "FATAL CRASH in thread ${thread.name}: ${throwable.message}", throwable)
                crashReporter.recordFatalException(threadName = thread.name, throwable = throwable)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Runtime watchdogs for ANR-like stalls and memory pressure.
        try {
            runtimeStabilityMonitor.start(this)
        } catch (e: Exception) {
            android.util.Log.e("SocialApp", "RuntimeStabilityMonitor start failed", e)
        }

        // Initialize background tasks safely - NO BLOQUEAR MAIN THREAD
        appScope.launch {
            try {
                // Monitor Metrics Alerts - Delay slightly to allow UI to settle
                launch {
                    try {
                        delay(1500)
                        appMetrics.alerts.collect { alert ->
                            if (alert.severity == MetricAlertSeverity.CRITICAL) {
                                crashReporter.recordMessage(
                                    message = "critical_metric_${alert.metric}",
                                    attributes = mapOf("msg" to alert.message) + alert.attributes
                                )
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SocialApp", "Alert observer failed", e)
                    }
                }
                
                // Start Telemetry - Staggered
                launch {
                    try {
                        delay(2500)
                        telemetryExporter.start()
                    } catch (e: Exception) {
                        android.util.Log.e("SocialApp", "Telemetry start failed", e)
                    }
                }
                
                // Start Sync - Staggered to avoid network contention with initial data load
                launch {
                    try {
                        delay(3500)
                        syncManager.start()
                    } catch (e: Exception) {
                        android.util.Log.e("SocialApp", "SyncManager start failed", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SocialApp", "Background global init error", e)
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        val performanceProfile = AppPerformanceProfiles.detect(this)
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(performanceProfile.memoryCachePercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizePercent(performanceProfile.diskCachePercent)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .components {
                add(coil.decode.VideoFrameDecoder.Factory())
            }
            .allowHardware(performanceProfile.tier != AppDeviceTier.LOW)
            .crossfade(false)
            .logger(if (BuildConfig.DEBUG) DebugLogger() else null)
            .build()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { productionOptimizer.onTrimMemory(level) }
            .onFailure { logger.w("SocialApp", "Production optimizer trim hook failed", it) }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            runCatching { Coil.imageLoader(this).memoryCache?.clear() }
                .onFailure { logger.w("SocialApp", "Image cache trim failed", it) }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { productionOptimizer.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }
            .onFailure { logger.w("SocialApp", "Production optimizer low memory hook failed", it) }
        runCatching { Coil.imageLoader(this).memoryCache?.clear() }
            .onFailure { logger.w("SocialApp", "Image cache clear failed", it) }
    }
}


