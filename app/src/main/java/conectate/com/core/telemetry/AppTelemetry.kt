package conectate.com.core.telemetry

import kotlinx.coroutines.flow.Flow

interface AppTelemetry {
    val events: Flow<TelemetryEvent>
    suspend fun track(event: TelemetryEvent)
}

