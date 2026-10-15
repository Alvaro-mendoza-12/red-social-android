package conectate.com

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.Coil
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
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
import dagger.Lazy
import javax.inject.Inject
import androidx.hilt.work.HiltWorkerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import conectate.com.util.AppDeviceTier
import conectate.com.util.AppPerformanceProfiles
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.webrtc.WebRTCManager
import java.util.concurrent.atomic.AtomicReference

@HiltAndroidApp
class SocialApp : Application(), ImageLoaderFactory, Configuration.Provider {
    
    @Inject lateinit var workerFactory: HiltWorkerFactory // Provisto por la integración Hilt/WorkManager.
    // Keep the application graph light. Incoming-call UI must be able to render
    // before analytics, sync and observability singletons are constructed.
    @Inject lateinit var crashReporter: Lazy<CrashReporter>
    @Inject lateinit var logger: Lazy<AppLogger>
    @Inject lateinit var appMetrics: Lazy<AppMetrics>
    @Inject lateinit var telemetryExporter: Lazy<SupabaseTelemetryExporter>
    @Inject lateinit var syncManager: Lazy<SyncManager>
    @Inject lateinit var runtimeStabilityMonitor: Lazy<RuntimeStabilityMonitor>
    
    // 🔥 PRODUCTION OPTIMIZER
    private val productionOptimizer = AtomicReference<ProductionReadyOptimizer?>(null)

    override val workManagerConfiguration: Configuration
        get() = try {
            check(::workerFactory.isInitialized) 
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()
        } catch (e: Exception) {
            // Current workers have public Context/WorkerParameters constructors and
            // can use WorkManager's default factory. Make a failed Hilt setup
            // visible: future injected workers would otherwise fail in the queue.
            android.util.Log.e("SocialApp", "Hilt worker factory unavailable; using default WorkManager factory", e)
            Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()
        }

    private val appScope = newSafeScope("SocialApp", Dispatchers.Default) // newSafeScope incluye SupervisorJob.

    override fun onCreate() {
        super.onCreate()

        RuntimeBackendEndpoint.initialize(this)
        
        // Coil discovers ImageLoaderFactory from the Application and creates the
        // loader on first image use. Avoid building its caches on the critical
        // cold-start path (especially when only showing an incoming call).
        
        // Initialize crash reporting. Telemetry must never prevent the app from opening.
        runCatching {
            FirebaseApp.initializeApp(this)
            Firebase.crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
            Firebase.crashlytics.setCustomKey("app_version", BuildConfig.VERSION_NAME)
            Firebase.crashlytics.setCustomKey("version_code", BuildConfig.VERSION_CODE)
        }.onFailure {
            android.util.Log.w("SocialApp", "Firebase/Crashlytics init skipped; app continues.", it)
        }

        if (BuildConfig.DEBUG) {
            android.util.Log.d("SocialApp", "APP_START: Novvin Social App initializing...")
        }
        
        // Setup unhandled exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                android.util.Log.e("SocialApp", "FATAL CRASH in thread ${thread.name}: ${throwable.message}", throwable)
                if (::crashReporter.isInitialized) {
                    crashReporter.get().recordFatalException(threadName = thread.name, throwable = throwable)
                }
            } catch (e: Exception) {
                android.util.Log.e("SocialApp", "Fatal-crash reporting failed", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Initialize background tasks safely - NO BLOQUEAR MAIN THREAD
        appScope.launch {
            try {
                // This touches the filesystem and scans a transient cache directory.
                // Initialize it off the main thread so a large cache cannot delay first draw.
                launch(Dispatchers.IO) {
                    try {
                        val optimizer = ProductionReadyOptimizer(this@SocialApp)
                        optimizer.initializeForProduction()
                        productionOptimizer.set(optimizer)
                    } catch (e: Exception) {
                        android.util.Log.e("SocialApp", "Production optimizer initialization failed", e)
                    }
                }
                launch {
                    RuntimeBackendEndpoint.refresh(this@SocialApp)
                }
                // Runtime watchdogs are important but do not need to delay the
                // first frame of an incoming call or launcher cold start.
                launch {
                    try {
                        delay(500)
                        runtimeStabilityMonitor.get().start(this@SocialApp)
                    } catch (e: Exception) {
                        android.util.Log.e("SocialApp", "RuntimeStabilityMonitor start failed", e)
                    }
                }
                // Monitor Metrics Alerts - Delay slightly to allow UI to settle
                launch {
                    try {
                        delay(1500)
                        appMetrics.get().alerts.collect { alert ->
                            if (alert.severity == MetricAlertSeverity.CRITICAL) {
                                crashReporter.get().recordMessage(
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
                        telemetryExporter.get().start()
                    } catch (e: Exception) {
                        android.util.Log.e("SocialApp", "Telemetry start failed", e)
                    }
                }
                
                // Start Sync - Staggered to avoid network contention with initial data load
                launch {
                    try {
                        delay(3500)
                        syncManager.get().start()
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
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .dns(conectate.com.core.network.PublicBackendDns)
            .addInterceptor(conectate.com.core.media.StorageReadRequestInterceptor())
            .addNetworkInterceptor(conectate.com.core.media.StorageReadNetworkInterceptor())
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val needsTunnelHeader = host.contains("ngrok", ignoreCase = true) ||
                    host.contains("trycloudflare.com", ignoreCase = true)
                if (!needsTunnelHeader) {
                    chain.proceed(request)
                } else {
                    chain.proceed(
                        request.newBuilder()
                            .header("ngrok-skip-browser-warning", "1")
                            .build()
                    )
                }
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
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
                add(conectate.com.core.media.StorageReadCoilInterceptor())
                add(coil.decode.VideoFrameDecoder.Factory())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .allowHardware(performanceProfile.tier != AppDeviceTier.LOW)
            .crossfade(false)
            .logger(if (BuildConfig.DEBUG) DebugLogger() else null)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { productionOptimizer.get()?.onTrimMemory(level) }
            .onFailure {
                if (::logger.isInitialized) {
                    logger.get().w("SocialApp", "Production optimizer trim hook failed", it)
                }
            }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching { Coil.imageLoader(this).memoryCache?.clear() }
                .onFailure {
                    if (::logger.isInitialized) {
                        logger.get().w("SocialApp", "Image cache trim failed", it)
                    }
                }
            WebRTCManager.releaseIdleResources()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { productionOptimizer.get()?.onLowMemory() }
            .onFailure {
                if (::logger.isInitialized) {
                    logger.get().w("SocialApp", "Production optimizer low memory hook failed", it)
                }
            }
        runCatching { Coil.imageLoader(this).memoryCache?.clear() }
            .onFailure {
                if (::logger.isInitialized) {
                    logger.get().w("SocialApp", "Image cache clear failed", it)
                }
            }
        WebRTCManager.releaseIdleResources()
    }
}


