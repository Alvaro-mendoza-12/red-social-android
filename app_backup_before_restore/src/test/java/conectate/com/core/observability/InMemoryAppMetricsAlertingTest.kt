package conectate.com.core.observability

import conectate.com.core.concurrency.DispatcherProvider
import conectate.com.core.crash.CrashReporter
import conectate.com.core.logging.AppLogger
import conectate.com.core.telemetry.AppTelemetry
import conectate.com.core.telemetry.TelemetryEvent
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryAppMetricsAlertingTest {

    @Test
    fun `realtime failures alert earlier than generic threshold`() = runBlocking {
        val metrics = buildMetrics()
        val observed = Collections.synchronizedList(mutableListOf<MetricAlert>())
        val collector = launch { metrics.alerts.collect { observed += it } }
        yield()

        repeat(4) {
            metrics.recordFailure(
                metric = "dm_realtime_subscription",
                throwable = IllegalStateException("realtime failure")
            )
        }

        withTimeout(2_000L) {
            while (observed.none { alert ->
                    alert.metric == "dm_realtime_subscription" &&
                        alert.severity == MetricAlertSeverity.WARNING
                }) {
                delay(25L)
            }
        }
        val alert = observed.first { item ->
            item.metric == "dm_realtime_subscription" &&
                item.severity == MetricAlertSeverity.WARNING
        }
        collector.cancel()
        assertEquals("Failure rate threshold exceeded", alert.message)
        assertEquals("4", alert.attributes["window_count"])
    }

    @Test
    fun `realtime failures escalate to critical inside same window`() = runBlocking {
        val metrics = buildMetrics()
        val observed = Collections.synchronizedList(mutableListOf<MetricAlert>())
        val collector = launch { metrics.alerts.collect { observed += it } }
        yield()

        repeat(10) {
            metrics.recordFailure(
                metric = "dm_realtime_subscription",
                throwable = IllegalStateException("realtime failure")
            )
        }

        withTimeout(2_000L) {
            while (observed.none { alert ->
                    alert.metric == "dm_realtime_subscription" &&
                        alert.severity == MetricAlertSeverity.CRITICAL
                }) {
                delay(25L)
            }
        }
        val critical = observed.first { item ->
            item.metric == "dm_realtime_subscription" &&
                item.severity == MetricAlertSeverity.CRITICAL
        }
        collector.cancel()
        assertEquals("Failure rate threshold exceeded", critical.message)
        assertTrue((critical.attributes["window_count"] ?: "0").toInt() >= 10)
    }

    @Test
    fun `reconnect pressure emits warning alert`() = runBlocking {
        val metrics = buildMetrics()
        val observed = Collections.synchronizedList(mutableListOf<MetricAlert>())
        val collector = launch { metrics.alerts.collect { observed += it } }
        yield()

        repeat(6) {
            metrics.incrementCounter(
                metric = "dm_realtime_reconnect_attempt",
                attributes = mapOf("reason" to "cancellation")
            )
        }

        withTimeout(2_000L) {
            while (observed.none { alert ->
                    alert.metric == "dm_realtime_reconnect_attempt" &&
                        alert.severity == MetricAlertSeverity.WARNING
                }) {
                delay(25L)
            }
        }
        val alert = observed.first { item ->
            item.metric == "dm_realtime_reconnect_attempt" &&
                item.severity == MetricAlertSeverity.WARNING
        }
        collector.cancel()
        assertEquals("Reconnect pressure detected", alert.message)
        assertEquals("6", alert.attributes["window_count"])
    }

    @Test
    fun `memory pressure counter emits warning alert`() = runBlocking {
        val metrics = buildMetrics()
        val observed = Collections.synchronizedList(mutableListOf<MetricAlert>())
        val collector = launch { metrics.alerts.collect { observed += it } }
        yield()

        repeat(2) {
            metrics.incrementCounter(
                metric = "memory_pressure_event",
                attributes = mapOf("source" to "trim_memory")
            )
        }

        withTimeout(2_000L) {
            while (observed.none { alert ->
                    alert.metric == "memory_pressure_event" &&
                        alert.severity == MetricAlertSeverity.WARNING
                }) {
                delay(25L)
            }
        }
        val alert = observed.first { item ->
            item.metric == "memory_pressure_event" &&
                item.severity == MetricAlertSeverity.WARNING
        }
        collector.cancel()
        assertEquals("Memory pressure events detected", alert.message)
        assertEquals("2", alert.attributes["window_count"])
    }
}

private fun buildMetrics(): InMemoryAppMetrics {
    return InMemoryAppMetrics(
        dispatchers = UnconfinedDispatcherProvider,
        telemetry = NoOpTelemetry(),
        logger = NoOpLogger(),
        crashReporter = NoOpCrashReporter()
    )
}

private object UnconfinedDispatcherProvider : DispatcherProvider {
    override val io = Dispatchers.Unconfined
    override val default = Dispatchers.Unconfined
    override val main = Dispatchers.Unconfined
    override val mainImmediate = Dispatchers.Unconfined
}

private class NoOpTelemetry : AppTelemetry {
    override val events: Flow<TelemetryEvent> = emptyFlow()
    override suspend fun track(event: TelemetryEvent) = Unit
}

private class NoOpLogger : AppLogger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}

private class NoOpCrashReporter : CrashReporter {
    override fun setUserId(userId: String?) = Unit
    override fun recordException(throwable: Throwable, extra: Map<String, String>) = Unit
    override fun recordFatalException(threadName: String, throwable: Throwable) = Unit
    override fun recordMessage(message: String, attributes: Map<String, String>) = Unit
}
