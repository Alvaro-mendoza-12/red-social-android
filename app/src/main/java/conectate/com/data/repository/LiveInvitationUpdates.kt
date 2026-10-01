package conectate.com.data.repository

import conectate.com.data.localfallback.UbuntuRealtimeEnvelope
import conectate.com.data.model.LiveGuestInvitation
import java.time.Instant
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive

internal fun isLiveInvitationChannelReady(
    envelope: UbuntuRealtimeEnvelope,
    expectedUserId: String,
    currentUserId: String?,
): Boolean = expectedUserId.isNotBlank() &&
    expectedUserId.equals(currentUserId?.trim(), ignoreCase = true) &&
    envelope.channel.equals("user:$expectedUserId", ignoreCase = true) &&
    envelope.event == "subscription_ready"

internal fun isLiveInvitationUpdate(
    envelope: UbuntuRealtimeEnvelope,
    liveId: String,
    expectedUserId: String,
    currentUserId: String?,
): Boolean = expectedUserId.isNotBlank() && liveId.isNotBlank() &&
    expectedUserId.equals(currentUserId?.trim(), ignoreCase = true) &&
    envelope.channel.equals("user:$expectedUserId", ignoreCase = true) &&
    envelope.event == "live_collaboration" &&
    (envelope.payload["liveId"] as? JsonPrimitive)?.contentOrNull.equals(liveId, ignoreCase = true) &&
    envelope.payload.containsKey("invitation")

/** HTTP snapshots can arrive after a newer push or action response. Keep the
 * newest server version, so a late pending snapshot cannot undo acceptance. */
internal fun mergeLiveInvitations(
    current: List<LiveGuestInvitation>,
    incoming: List<LiveGuestInvitation>,
    liveId: String,
    userId: String,
): List<LiveGuestInvitation> {
    fun belongs(item: LiveGuestInvitation) = userId.isNotBlank() && item.id.isNotBlank() &&
        item.liveId.equals(liveId, ignoreCase = true) &&
        (item.hostUserId.equals(userId, ignoreCase = true) || item.inviteeUserId.equals(userId, ignoreCase = true))
    fun timestamp(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
    fun version(item: LiveGuestInvitation) = timestamp(item.updatedAt) ?: timestamp(item.respondedAt)
        ?: timestamp(item.createdAt) ?: Instant.MIN
    fun statusOrder(item: LiveGuestInvitation) = when (item.status.lowercase()) {
        "canceled", "expired" -> 2
        "accepted", "rejected" -> 1
        else -> 0
    }
    val merged = linkedMapOf<String, LiveGuestInvitation>()
    (current + incoming).filter(::belongs).forEach { item ->
        val key = item.id.lowercase()
        val previous = merged[key]
        if (previous == null || version(item) > version(previous) ||
            (version(item) == version(previous) && statusOrder(item) >= statusOrder(previous))) merged[key] = item
    }
    return merged.values.sortedByDescending { timestamp(it.createdAt) ?: version(it) }.take(50)
}
