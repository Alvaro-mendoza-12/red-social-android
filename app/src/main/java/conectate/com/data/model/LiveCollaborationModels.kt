package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LiveCollaborationCapabilities(
    @SerialName("guestsBattlesEnabled") val guestsBattlesEnabled: Boolean = false,
    @SerialName("sfuAvailable") val sfuAvailable: Boolean = false,
    @SerialName("channelPointsEnabled") val channelPointsEnabled: Boolean = false,
    @SerialName("drawGuessEnabled") val drawGuessEnabled: Boolean = false,
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
    @SerialName("initiated_by") val initiatedBy: String = "host",
    val status: String = "pending",
    @SerialName("request_nonce") val requestNonce: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("responded_at") val respondedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class LiveParticipantState(
    val id: String = "",
    @SerialName("live_id") val liveId: String = "",
    @SerialName("user_id") val userId: String = "",
    val role: String = "viewer",
    @SerialName("participant_identity") val participantIdentity: String = "",
    val state: String = "invited",
    @SerialName("joined_at") val joinedAt: String? = null,
    @SerialName("left_at") val leftAt: String? = null,
    @SerialName("audio_muted_by_host") val audioMutedByHost: Boolean = false
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

@Serializable
data class LiveSupporter(
    @SerialName("user_id") val userId: String = "",
    val name: String = "Usuario",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("coins_in_live") val coinsInLive: Int = 0,
    @SerialName("lifetime_coins") val lifetimeCoins: Int = 0,
    val rank: Int = 0,
    val level: Int = 1,
    val badge: String = "Brisa"
)
