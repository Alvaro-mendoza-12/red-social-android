package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LiveCollaborationCapabilities(
    @SerialName("guestsBattlesEnabled") val guestsBattlesEnabled: Boolean = false,
    @SerialName("sfuAvailable") val sfuAvailable: Boolean = false,
    @SerialName("channelPointsEnabled") val channelPointsEnabled: Boolean = false,
    @SerialName("maxGuests") val maxGuests: Int = 0,
    @SerialName("scoringSources") val scoringSources: List<String> = emptyList()
)

@Serializable
data class LiveSfuSession(
    val wsUrl: String = "",
    val roomName: String = "",
    val token: String = "",
    val expiresAt: Long = 0L,
    val role: String = "viewer",
    val maxGuests: Int = 0
)

@Serializable
data class LiveGuestInvitation(
    val id: String = "",
    @SerialName("live_id") val liveId: String = "",
    @SerialName("host_user_id") val hostUserId: String = "",
    @SerialName("invitee_user_id") val inviteeUserId: String = "",
    val status: String = "pending",
    @SerialName("request_nonce") val requestNonce: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("responded_at") val respondedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class LiveBattle(
    val id: String = "",
    @SerialName("live_id") val liveId: String = "",
    @SerialName("left_user_id") val leftUserId: String = "",
    @SerialName("right_user_id") val rightUserId: String = "",
    val status: String = "active",
    @SerialName("score_version") val scoreVersion: Long = 0L,
    @SerialName("left_score") val leftScore: Long = 0L,
    @SerialName("right_score") val rightScore: Long = 0L,
    @SerialName("winner_user_id") val winnerUserId: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
