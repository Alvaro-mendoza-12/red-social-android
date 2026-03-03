package conectate.com.data.observability

import conectate.com.BuildConfig
import conectate.com.core.concurrency.DispatcherProvider
import conectate.com.core.logging.AppLogger
import conectate.com.core.telemetry.AppTelemetry
import conectate.com.core.telemetry.TelemetryEvent
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class TelemetryIngestRow(
    @SerialName("event_name") val eventName: String,
    @SerialName("attributes") val attributes: JsonObject,
    @SerialName("client_ts") val clientTs: String,
    @SerialName("session_user_id") val sessionUserId: String? = null,
    @SerialName("app_version") val appVersion: String,
    @SerialName("platform") val platform: String = "android"
)

@Singleton
class SupabaseTelemetryExporter @Inject constructor(
    private val telemetry: AppTelemetry,
    private val authService: AuthService,
    private val supabaseConfig: SupabaseConfig,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) {

    private val started = AtomicBoolean(false)
    private val disabledUntilMs = AtomicLong(0L)
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val queueMutex = Mutex()
    private val flushMutex = Mutex()
    private val queue = ArrayDeque<TelemetryEvent>()

    fun start() {
        if (!started.compareAndSet(false, true)) return

        scope.launch {
            telemetry.events.collect { event ->
                enqueue(event)
            }
        }

        scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushIfNeeded()
            }
        }
    }

    private suspend fun enqueue(event: TelemetryEvent) {
        queueMutex.withLock {
            queue.addLast(event)
            while (queue.size > MAX_QUEUE_SIZE) {
                queue.removeFirstOrNull()
            }
        }

        if (queue.size >= BATCH_SIZE) {
            flushIfNeeded()
        }
    }

    private suspend fun flushIfNeeded() {
        if (disabledUntilMs.get() > System.currentTimeMillis()) return
        if (!flushMutex.tryLock()) return

        try {
            val batch = queueMutex.withLock {
                if (queue.isEmpty()) {
                    emptyList()
                } else {
                    buildList {
                        repeat(minOf(BATCH_SIZE, queue.size)) {
                            queue.removeFirstOrNull()?.let { add(it) }
                        }
                    }
                }
            }

            if (batch.isEmpty()) return

            val token = authService.getAccessToken()
            if (token.isNullOrBlank()) {
                // Keep a limited backlog for the first authenticated session.
                requeueAtFront(batch)
                return
            }

            val requestBody = batch.map { event ->
                TelemetryIngestRow(
                    eventName = event.name.take(MAX_EVENT_NAME_LENGTH),
                    attributes = event.attributes.toJsonObject(MAX_ATTRIBUTES_PER_EVENT),
                    clientTs = Instant.ofEpochMilli(event.timestampMs).toString(),
                    sessionUserId = authService.getCurrentUserId(),
                    appVersion = BuildConfig.VERSION_NAME
                )
            }

            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/app_telemetry_events") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=minimal")
                setBody(requestBody)
            }

            if (!response.status.isSuccess()) {
                val errorBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
                handleFlushFailure(
                    statusCode = response.status.value,
                    body = errorBody,
                    failedBatch = batch
                )
            }
        } catch (error: Exception) {
            logger.w(TAG, "Telemetry flush failed: ${error.message}")
        } finally {
            flushMutex.unlock()
        }
    }

    private suspend fun handleFlushFailure(
        statusCode: Int,
        body: String,
        failedBatch: List<TelemetryEvent>
    ) {
        val cleanBody = body.lowercase()
        val missingTable = cleanBody.contains("app_telemetry_events") &&
            (cleanBody.contains("does not exist") || cleanBody.contains("undefined table"))
        if (missingTable) {
            disabledUntilMs.set(System.currentTimeMillis() + DISABLE_ON_SCHEMA_ERROR_MS)
            queueMutex.withLock { queue.clear() }
            logger.w(TAG, "Telemetry export disabled temporarily: backend table missing")
            return
        }

        val retryable = statusCode == 429 || statusCode >= 500
        if (retryable) {
            requeueAtFront(failedBatch)
        }
        logger.w(TAG, "Telemetry flush rejected ($statusCode): $body")
    }

    private suspend fun requeueAtFront(events: List<TelemetryEvent>) {
        if (events.isEmpty()) return
        queueMutex.withLock {
            for (index in events.indices.reversed()) {
                queue.addFirst(events[index])
            }
            while (queue.size > MAX_QUEUE_SIZE) {
                queue.removeLastOrNull()
            }
        }
    }

    private fun Map<String, String>.toJsonObject(maxAttributes: Int): JsonObject {
        val keys = keys.sorted().take(maxAttributes)
        return buildJsonObject {
            keys.forEach { key ->
                val value = this@toJsonObject[key].orEmpty()
                put(key.take(80), JsonPrimitive(value.take(600)))
            }
        }
    }

    private companion object {
        private const val TAG = "TelemetryExporter"
        private const val BATCH_SIZE = 25
        private const val MAX_QUEUE_SIZE = 600
        private const val FLUSH_INTERVAL_MS = 12_000L
        private const val DISABLE_ON_SCHEMA_ERROR_MS = 20 * 60_000L
        private const val MAX_EVENT_NAME_LENGTH = 80
        private const val MAX_ATTRIBUTES_PER_EVENT = 20
    }
}
