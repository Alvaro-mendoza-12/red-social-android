package conectate.com.core.security

import conectate.com.core.logging.AppLogger
import conectate.com.core.telemetry.AppTelemetry
import conectate.com.core.telemetry.TelemetryEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class OperationGuardStressTest {

    @Test
    fun `double tap on same scope is throttled`() = runBlocking {
        val telemetry = RecordingTelemetry()
        val guard = OperationGuard(telemetry = telemetry, logger = NoOpLogger())

        val first = guard.acquire(
            action = GuardedAction.DIRECT_MESSAGE_SEND,
            actorId = "user-1",
            scopeId = "chat-1"
        )
        val second = guard.acquire(
            action = GuardedAction.DIRECT_MESSAGE_SEND,
            actorId = "user-1",
            scopeId = "chat-1"
        )

        assertTrue(first.allowed)
        assertTrue(!second.allowed)
        assertTrue(second.retryAfterMs > 0L)
    }

    @Test
    fun `different scopes do not block each other`() = runBlocking {
        val guard = OperationGuard(telemetry = RecordingTelemetry(), logger = NoOpLogger())

        val firstScope = guard.acquire(
            action = GuardedAction.DIRECT_MESSAGE_SEND,
            actorId = "user-1",
            scopeId = "chat-a"
        )
        val secondScope = guard.acquire(
            action = GuardedAction.DIRECT_MESSAGE_SEND,
            actorId = "user-1",
            scopeId = "chat-b"
        )

        assertTrue(firstScope.allowed)
        assertTrue(secondScope.allowed)
    }

    @Test
    fun `concurrent spam burst is mostly blocked`() = runBlocking {
        val guard = OperationGuard(telemetry = RecordingTelemetry(), logger = NoOpLogger())
        val decisions = Collections.synchronizedList(mutableListOf<GuardDecision>())

        coroutineScope {
            repeat(24) {
                launch(Dispatchers.Default) {
                    decisions += guard.acquire(
                        action = GuardedAction.DIRECT_MESSAGE_SEND,
                        actorId = "user-burst",
                        scopeId = "chat-burst"
                    )
                }
            }
        }

        val allowed = decisions.count { it.allowed }
        val blocked = decisions.count { !it.allowed }

        assertEquals(1, allowed)
        assertTrue(blocked >= 23)
    }

    @Test
    fun `blocked attempts emit security audit telemetry`() = runBlocking {
        val telemetry = RecordingTelemetry()
        val guard = OperationGuard(telemetry = telemetry, logger = NoOpLogger())

        guard.acquire(
            action = GuardedAction.DIRECT_MESSAGE_SEND,
            actorId = "user-audit",
            scopeId = "chat-audit"
        )
        guard.acquire(
            action = GuardedAction.DIRECT_MESSAGE_SEND,
            actorId = "user-audit",
            scopeId = "chat-audit"
        )

        val events = telemetry.snapshot()
        assertTrue(
            events.any { event ->
                event.name == "security_audit" &&
                    event.attributes["action"] == "direct_message_send" &&
                    event.attributes["outcome"] == "blocked"
            }
        )
    }
}

private class RecordingTelemetry : AppTelemetry {
    private val _events = MutableSharedFlow<TelemetryEvent>(extraBufferCapacity = 64)
    private val stored = Collections.synchronizedList(mutableListOf<TelemetryEvent>())

    override val events: Flow<TelemetryEvent> = _events.asSharedFlow()

    override suspend fun track(event: TelemetryEvent) {
        stored += event
        _events.emit(event)
    }

    fun snapshot(): List<TelemetryEvent> = stored.toList()
}

private class NoOpLogger : AppLogger {
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}

