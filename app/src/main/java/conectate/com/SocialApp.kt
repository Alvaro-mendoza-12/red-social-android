package conectate.com

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import androidx.work.Configuration
import conectate.com.core.crash.CrashReporter
import conectate.com.core.logging.AppLogger
import conectate.com.core.observability.AppMetrics
import conectate.com.core.observability.MetricAlertSeverity
import conectate.com.core.stability.RuntimeStabilityMonitor
import conectate.com.data.observability.SupabaseTelemetryExporter
import conectate.com.data.sync.SyncManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import androidx.hilt.work.HiltWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@HiltAndroidApp
class SocialApp : Application(), ImageLoaderFactory, Configuration.Provider {
    
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var crashReporter: CrashReporter
    @Inject lateinit var logger: AppLogger
    @Inject lateinit var appMetrics: AppMetrics
    @Inject lateinit var telemetryExporter: SupabaseTelemetryExporter
    @Inject lateinit var syncManager: SyncManager
    @Inject lateinit var runtimeStabilityMonitor: RuntimeStabilityMonitor

    override val workManagerConfiguration: Configuration
        get() = try {
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()
        } catch (e: Exception) {
            // Extremadamente defensivo para evitar crash si se llama antes de la inyección
            Configuration.Builder().build()
        }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

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
                // Monitor Metrics Alerts
                launch {
                    try {
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
                
                // Start Telemetry
                launch {
                    try {
                        telemetryExporter.start()
                    } catch (e: Exception) {
                        android.util.Log.e("SocialApp", "Telemetry start failed", e)
                    }
                }
                
                // Start Sync
                launch {
                    try {
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
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.08)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .components {
                add(coil.decode.VideoFrameDecoder.Factory())
            }
            .allowHardware(true)
            .crossfade(false)
            .logger(if (BuildConfig.DEBUG) DebugLogger() else null)
            .build()
    }
}
