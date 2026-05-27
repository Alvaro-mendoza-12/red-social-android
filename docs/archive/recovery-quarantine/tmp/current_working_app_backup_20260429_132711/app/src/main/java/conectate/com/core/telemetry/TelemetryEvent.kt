package conectate.com.core.telemetry

data class TelemetryEvent(
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis()
)

