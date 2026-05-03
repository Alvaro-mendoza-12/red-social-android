package conectate.com.core.observability

import conectate.com.core.concurrency.DispatcherProvider
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.crash.CrashReporter
import conectate.com.core.logging.AppLogger
import conectate.com.core.telemetry.AppTelemetry
import conectate.com.core.telemetry.TelemetryEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class MetricAlertSeverity {
    WARNING,
    CRITICAL
}

data class MetricAlert(
    val metric: String,
    val severity: MetricAlertSeverity,
    val message: String,
    val attributes: Map<String, String> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis()
)

interface AppMetrics {
    val alerts: Flow<MetricAlert>
    fun incrementCounter(metric: String, delta: Long = 1, attributes: Map<String, String> = emptyMap())
    fun recordLatency(metric: String, durationMs: Long, attributes: Map<String, String> = emptyMap())
    fun recordFailure(metric: String, throwable: Throwable? = null, attributes: Map<String, String> = emptyMap())
}

@Singleton
class InMemoryAppMetrics @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val telemetry: AppTelemetry,
    private val logger: AppLogger,
    private val crashReporter: CrashReporter
) : AppMetrics {

    private val scope = newSafeScope("InMemoryAppMetrics", dispatchers.io)
    private val counterMap = ConcurrentHashMap<String, AtomicLong>()
    private val failureWindows = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val counterAlertWindows = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val alertCooldownMs = 60_000L
    private val lastAlertAt = ConcurrentHashMap<String, AtomicLong>()

    private val alertFlow = MutableSharedFlow<MetricAlert>(
        replay = 0,
        extraBufferCapacity = 64
    )
    override val alerts: Flow<MetricAlert> = alertFlow.asSharedFlow()

    override fun incrementCounter(metric: String, delta: Long, attributes: Map<String, String>) {
        val value = counterMap.getOrPut(metric) { AtomicLong(0) }.addAndGet(delta)
        trackMetric(
            metric = "${metric}_count",
            attributes = attributes + ("value" to value.toString())
        )
        evaluateCounterAlertPressure(metric = metric, attributes = attributes)
    }

    override fun recordLatency(metric: String, durationMs: Long, attributes: Map<String, String>) {
        trackMetric(
            metric = "${metric}_latency_ms",
            attributes = attributes + ("duration_ms" to durationMs.toString())
        )

        if (durationMs >= 4_000L) {
            maybeEmitAlert(
                key = "latency:$metric",
                alert = MetricAlert(
                    metric = metric,
                    severity = MetricAlertSeverity.WARNING,
                    message = "High latency detected",
                    attributes = attributes + ("duration_ms" to durationMs.toString())
                )
            )
        }
    }

    override fun recordFailure(metric: String, throwable: Throwable?, attributes: Map<String, String>) {
        var now = System.currentTimeMillis()
        var windowCount = appendAndCountInWindow(
            windows = failureWindows,
            key = metric,
            now = now,
            windowMs = FAILURE_WINDOW_MS
        )

        trackMetric(
            metric = "${metric}_failure",
            attributes = attributes + ("window_count" to windowCount.toString())
        )

        if (throwable != null) {
            crashReporter.recordException(
                throwable = throwable,
                extra = attributes + ("metric" to metric)
            )
        }

        var lowerMetric = metric.lowercase()
        val warningThreshold = if (lowerMetric.contains("realtime")) {
            REALTIME_FAILURE_WARNING_THRESHOLD
        } else {
            FAILURE_WARNING_THRESHOLD
        }
        val criticalThreshold = if (lowerMetric.contains("realtime")) {
            REALTIME_FAILURE_CRITICAL_THRESHOLD
        } else {
            FAILURE_CRITICAL_THRESHOLD
        }
        var severity = if (windowCount >= criticalThreshold) {
            MetricAlertSeverity.CRITICAL
        } else {
            MetricAlertSeverity.WARNING
        }
        val thresholdHit = windowCount >= warningThreshold
        if (thresholdHit) {
            maybeEmitAlert(
                key = "failure:$metric:${severity.name.lowercase()}",
                alert = MetricAlert(
                    metric = metric,
                    severity = severity,
                    message = "Failure rate threshold exceeded",
                    attributes = attributes + ("window_count" to windowCount.toString())
                )
            )
        }
    }

    private fun evaluateCounterAlertPressure(metric: String, attributes: Map<String, String>) {
        var lowerMetric = metric.lowercase()
        var now = System.currentTimeMillis()

        if (lowerMetric.contains("reconnect")) {
            var windowCount = appendAndCountInWindow(
                windows = counterAlertWindows,
                key = "reconnect:$metric",
                now = now,
                windowMs = RECONNECT_WINDOW_MS
            )
            var severity = when {
                windowCount >= RECONNECT_CRITICAL_THRESHOLD -> MetricAlertSeverity.CRITICAL
                windowCount >= RECONNECT_WARNING_THRESHOLD -> MetricAlertSeverity.WARNING
                else -> null
            } ?: return

            maybeEmitAlert(
                key = "counter:$metric:${severity.name.lowercase()}",
                alert = MetricAlert(
                    metric = metric,
                    severity = severity,
                    message = "Reconnect pressure detected",
                    attributes = attributes + ("window_count" to windowCount.toString())
                )
            )
            return
        }

        if (lowerMetric.startsWith("memory_pressure")) {
            var windowCount = appendAndCountInWindow(
                windows = counterAlertWindows,
                key = "memory:$metric",
                now = now,
                windowMs = MEMORY_PRESSURE_WINDOW_MS
            )
            var severity = when {
                windowCount >= MEMORY_PRESSURE_CRITICAL_THRESHOLD -> MetricAlertSeverity.CRITICAL
                windowCount >= MEMORY_PRESSURE_WARNING_THRESHOLD -> MetricAlertSeverity.WARNING
                else -> null
            } ?: return

            maybeEmitAlert(
                key = "counter:$metric:${severity.name.lowercase()}",
                alert = MetricAlert(
                    metric = metric,
                    severity = severity,
                    message = "Memory pressure events detected",
                    attributes = attributes + ("window_count" to windowCount.toString())
                )
            )
        }
    }

    private fun appendAndCountInWindow(
        windows: ConcurrentHashMap<String, ArrayDeque<Long>>,
        key: String,
        now: Long,
        windowMs: Long
    ): Int {
        val window = windows.computeIfAbsent(key) { ArrayDeque() }
        return synchronized(window) {
            while (window.isNotEmpty() && now - window.first() > windowMs) {
                window.removeFirst()
            }
            window.addLast(now)
            window.size
        }
    }

    private fun maybeEmitAlert(key: String, alert: MetricAlert) {
        var now = System.currentTimeMillis()
        val last = lastAlertAt.getOrPut(key) { AtomicLong(0) }.get()
        if (now - last < alertCooldownMs) return
        lastAlertAt[key]?.set(now)

        scope.launch {
            alertFlow.emit(alert)
            logger.w(
                "AppMetrics",
                "ALERT ${alert.severity} metric=${alert.metric} message=${alert.message} attrs=${alert.attributes}"
            )
            trackMetric(
                metric = "metric_alert",
                attributes = mapOf(
                    "metric" to alert.metric,
                    "severity" to alert.severity.name.lowercase(),
                    "message" to alert.message
                ) + alert.attributes
            )
        }
    }

    private fun trackMetric(metric: String, attributes: Map<String, String>) {
        scope.launch {
            runCatching {
                telemetry.track(TelemetryEvent(name = metric, attributes = attributes))
            }
        }
    }

    private companion object {
        private const val FAILURE_WINDOW_MS = 5 * 60_000L
        private const val FAILURE_WARNING_THRESHOLD = 8
        private const val FAILURE_CRITICAL_THRESHOLD = 15
        private const val REALTIME_FAILURE_WARNING_THRESHOLD = 4
        private const val REALTIME_FAILURE_CRITICAL_THRESHOLD = 10
        private const val RECONNECT_WINDOW_MS = 2 * 60_000L
        private const val RECONNECT_WARNING_THRESHOLD = 6
        private const val RECONNECT_CRITICAL_THRESHOLD = 12
        private const val MEMORY_PRESSURE_WINDOW_MS = 10 * 60_000L
        private const val MEMORY_PRESSURE_WARNING_THRESHOLD = 2
        private const val MEMORY_PRESSURE_CRITICAL_THRESHOLD = 4
    }
}




