package conectate.com.data.localfallback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MarkerMessagePreview(
    val id: String = "",
    @SerialName("sender_id") val senderId: String = "",
    val content: String = "",
    @SerialName("content_type") val contentType: String = "text",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class PinnedMessageMarker(
    @SerialName("message_id") val messageId: String = "",
    @SerialName("pinned_by") val pinnedBy: String = "",
    @SerialName("pinned_by_name") val pinnedByName: String = "",
    @SerialName("pinned_at") val pinnedAt: String = "",
    val message: MarkerMessagePreview? = null,
    val unavailable: Boolean = false,
)

@Serializable
data class SavedMessageMarker(
    @SerialName("message_id") val messageId: String = "",
    val label: String = "",
    @SerialName("saved_at") val savedAt: String = "",
    val message: MarkerMessagePreview? = null,
    val unavailable: Boolean = false,
)

@Serializable
data class MessageMarkersResponse(
    val pins: List<PinnedMessageMarker> = emptyList(),
    val saved: List<SavedMessageMarker> = emptyList(),
    val canPin: Boolean = false,
)

@Serializable
data class GlobalSavedMessage(
    @SerialName("room_kind") val roomKind: String = "",
    @SerialName("room_id") val roomId: String = "",
    @SerialName("room_name") val roomName: String = "",
    @SerialName("message_id") val messageId: String = "",
    val label: String = "",
    @SerialName("saved_at") val savedAt: String = "",
    val message: MarkerMessagePreview? = null,
    val unavailable: Boolean = false,
)

@Serializable
data class GlobalSavedMessagesResponse(
    val saved: List<GlobalSavedMessage> = emptyList(),
    val nextCursor: String? = null,
)
