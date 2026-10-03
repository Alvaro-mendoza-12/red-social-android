package conectate.com.data.repository

import conectate.com.data.localfallback.UbuntuRealtimeEnvelope
import conectate.com.data.model.Notification
import conectate.com.util.isRetiredNotification
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/** Per subscriber and account: bad or retried envelopes never stop the notification stream. */
internal class NotificationEventGate(accountId: String, private val capacity: Int = 512) {
    private val owner = accountId.trim().lowercase()
    private val seen = LinkedHashSet<String>()

    init { require(capacity > 0) }

    fun accept(envelope: UbuntuRealtimeEnvelope, currentAccount: String?, json: Json): Notification? {
        if (!owner.equals(currentAccount?.trim(), ignoreCase = true) ||
            !envelope.channel.equals("user:$owner", ignoreCase = true) || envelope.event != "notification") return null
        val payload = envelope.payload["notification"] as? JsonObject ?: return null
        // The model has a UUID default; an event without an actual server ID is not a new notification.
        val id = (payload["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val notice = runCatching { json.decodeFromJsonElement<Notification>(payload) }.getOrNull() ?: return null
        if (!notice.recipientId.equals(owner, ignoreCase = true) || notice.type.isBlank()) return null
        if (notice.isRetiredNotification()) return null
        if (!seen.add(id)) return null
        if (seen.size > capacity) seen.remove(seen.first())
        return notice
    }
}
