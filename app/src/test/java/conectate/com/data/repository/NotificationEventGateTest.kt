package conectate.com.data.repository

import conectate.com.data.localfallback.UbuntuRealtimeEnvelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NotificationEventGateTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun envelope(id: String = "notice-1", recipient: String = "qa-owner") = UbuntuRealtimeEnvelope(
        "user:qa-owner", "notification", JsonObject(mapOf("notification" to JsonObject(mapOf(
            "id" to JsonPrimitive(id), "recipient_id" to JsonPrimitive(recipient),
            "actor_id" to JsonPrimitive("qa-actor"), "type" to JsonPrimitive("system_announcement"),
        )))),
    )
    @Test fun duplicateDeliveryIsOnlyEmittedOncePerSubscriber() {
        val gate = NotificationEventGate("QA-OWNER")
        assertNotNull(gate.accept(envelope(), "qa-owner", json))
        assertNull(gate.accept(envelope(), "qa-owner", json))
    }
    @Test fun brokenPayloadDoesNotPreventTheNextValidDelivery() {
        val gate = NotificationEventGate("qa-owner")
        assertNull(gate.accept(envelope().copy(payload = JsonObject(mapOf("notification" to JsonPrimitive("broken")))), "qa-owner", json))
        assertNotNull(gate.accept(envelope(), "qa-owner", json))
    }
    @Test fun missingServerIdDoesNotGenerateAClientNotification() {
        val gate = NotificationEventGate("qa-owner")
        val original = envelope().payload["notification"] as JsonObject
        assertNull(gate.accept(envelope().copy(payload = JsonObject(mapOf("notification" to JsonObject(original - "id")))), "qa-owner", json))
        assertNotNull(gate.accept(envelope(), "qa-owner", json))
    }
    @Test fun accountChangeAndForeignRecipientsAreRejected() {
        val gate = NotificationEventGate("qa-owner")
        assertNull(gate.accept(envelope(), "other-account", json))
        assertNull(gate.accept(envelope(recipient = "other-account"), "qa-owner", json))
        assertNull(gate.accept(envelope().copy(channel = "user:other-account"), "qa-owner", json))
        assertNotNull(gate.accept(envelope(), "qa-owner", json))
    }
    @Test fun duplicateMemoryIsBoundedAndIndependentBetweenSubscribers() {
        val gate = NotificationEventGate("qa-owner", capacity = 2)
        (1..3).forEach { assertNotNull(gate.accept(envelope("notice-$it"), "qa-owner", json)) }
        assertNull(gate.accept(envelope("notice-3"), "qa-owner", json))
        assertNotNull(gate.accept(envelope("notice-1"), "qa-owner", json))
        assertNotNull(NotificationEventGate("qa-owner").accept(envelope("notice-3"), "qa-owner", json))
    }
}
