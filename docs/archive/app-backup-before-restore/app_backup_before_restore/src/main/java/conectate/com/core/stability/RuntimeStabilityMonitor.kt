package conectate.com.core.stability

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import conectate.com.core.concurrency.DispatcherProvider
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.crash.CrashReporter
import conectate.com.core.logging.AppLogger
import conectate.com.core.observability.AppMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RuntimeStabilityMonitor @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val appMetrics: AppMetrics,
    private val crashReporter: CrashReporter,
    private val logger: AppLogger
) : DefaultLifecycleObserver {

    private val started = AtomicBoolean(false)
    private val isForeground = AtomicBoolean(true)
    private val scope = newSafeScope("RuntimeStabilityMonitor", dispatchers.default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastStallReportAtMs = AtomicLong(0L)
    private val lastMemoryReportAtMs = AtomicLong(0L)

    @Volatile
    private var lastMainPulseAtMs: Long = SystemClock.uptimeMillis()

    private val mainPulse = object : Runnable {
        override fun run() {
            lastMainPulseAtMs = SystemClock.uptimeMillis()
            mainHandler.postDelayed(this, MAIN_PULSE_INTERVAL_MS)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                handleMemoryPressure(source = "trim_memory", level = level)
            }
        }

        override fun onLowMemory() {
            handleMemoryPressure(source = "low_memory", level = 100)
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    fun start(application: Application) {
        if (!started.compareAndSet(false, true)) return

        application.registerComponentCallbacks(memoryCallbacks)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        mainHandler.post(mainPulse)
        startMainThreadWatchdog()
        logger.i(TAG, "RuntimeStabilityMonitor started")
    }

    override fun onStart(owner: LifecycleOwner) {
        isForeground.set(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        isForeground.set(false)
    }

    private fun startMainThreadWatchdog() {
        scope.launch {
            while (isActive) {
                delay(MAIN_STALL_CHECK_INTERVAL_MS)
                if (!isForeground.get()) continue

                val lagMs = SystemClock.uptimeMillis() - lastMainPulseAtMs
                if (lagMs < MAIN_STALL_THRESHOLD_MS) continue

                var now = SystemClock.uptimeMillis()
                var lastReport = lastStallReportAtMs.get()
                if (now - lastReport < MAIN_STALL_REPORT_COOLDOWN_MS) continue
                lastStallReportAtMs.set(now)

                var attrs = mapOf(
                    "stall_ms" to lagMs.toString(),
                    "foreground" to "true"
                )
                appMetrics.recordFailure(metric = "main_thread_stall", attributes = attrs)
                crashReporter.recordMessage(
                    message = "main_thread_stall",
                    attributes = attrs + heapSnapshot()
                )
                logger.w(TAG, "Main thread stall detected (${lagMs}ms)")
            }
        }
    }

    private fun handleMemoryPressure(source: String, level: Int) {
        var attrs = mapOf(
            "source" to source,
            "level" to level.toString()
        )
        appMetrics.incrementCounter(metric = "memory_pressure_event", attributes = attrs)

        var now = SystemClock.uptimeMillis()
        var lastReport = lastMemoryReportAtMs.get()
        if (now - lastReport < MEMORY_REPORT_COOLDOWN_MS) return
        lastMemoryReportAtMs.set(now)

        crashReporter.recordMessage(
            message = "memory_pressure",
            attributes = attrs + heapSnapshot()
        )
        logger.w(TAG, "Memory pressure detected source=$source level=$level")
    }

    private fun heapSnapshot(): Map<String, String> {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        return mapOf("heap_mb" to "$usedMb/$maxMb")
    }

    private companion object {
        private const val TAG = "RuntimeStability"
        private const val MAIN_PULSE_INTERVAL_MS = 1_000L
        private const val MAIN_STALL_CHECK_INTERVAL_MS = 2_000L
        private const val MAIN_STALL_THRESHOLD_MS = 6_000L
        private const val MAIN_STALL_REPORT_COOLDOWN_MS = 60_000L
        private const val MEMORY_REPORT_COOLDOWN_MS = 60_000L
    }
}



