package conectate.com.core.telemetry

import conectate.com.core.logging.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryTelemetry @Inject constructor(
    private val logger: AppLogger
) : AppTelemetry {

    private val eventStream = MutableSharedFlow<TelemetryEvent>(
        replay = 0,
        extraBufferCapacity = 128
    )

    override val events: Flow<TelemetryEvent> = eventStream.asSharedFlow()

    override suspend fun track(event: TelemetryEvent) {
        eventStream.emit(event)
        logger.d("Telemetry", "${event.name} ${event.attributes}")
    }
}



