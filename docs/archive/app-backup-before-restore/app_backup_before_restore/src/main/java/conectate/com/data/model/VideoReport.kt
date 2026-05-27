package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VideoReport(
    @SerialName("id") val id: String,
    @SerialName("video_id") val videoId: String,
    @SerialName("video_owner_id") val videoOwnerId: String,
    @SerialName("video_owner_name") val videoOwnerName: String? = null,
    @SerialName("video_title") val videoTitle: String? = null,
    @SerialName("video_thumbnail_url") val videoThumbnailUrl: String? = null,
    @SerialName("reporter_id") val reporterId: String,
    @SerialName("reporter_name") val reporterName: String? = null,
    @SerialName("reason") val reason: String,
    @SerialName("details") val details: String? = null,
    @SerialName("status") val status: String = "pendiente",
    @SerialName("warn_sent") val warnSent: Boolean = false,
    @SerialName("strike_applied") val strikeApplied: Boolean = false,
    @SerialName("action_note") val actionNote: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)
