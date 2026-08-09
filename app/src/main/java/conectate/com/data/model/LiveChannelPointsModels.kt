package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LivePointsSession(
    val sessionToken: String,
    val sessionNonce: String,
    val intervalSeconds: Int,
    val pointsPerInterval: Int,
    val dailyCap: Int,
)

@Serializable
data class LivePointsHeartbeat(
    val awardedPoints: Int = 0,
    val balance: Int? = null,
    val eligible: Boolean = false,
    val dailyCapReached: Boolean = false,
    val serverTime: Long = 0,
)

@Serializable
data class LivePointsSettings(
    @SerialName("live_id") val liveId: String = "",
    @SerialName("creator_id") val creatorId: String = "",
    val enabled: Boolean = false,
    @SerialName("interval_seconds") val intervalSeconds: Int = 300,
    @SerialName("points_per_interval") val pointsPerInterval: Int = 10,
    @SerialName("daily_cap") val dailyCap: Int = 2_000,
)

@Serializable
data class LivePointsBalance(
    @SerialName("live_id") val liveId: String = "",
    @SerialName("creator_id") val creatorId: String = "",
    @SerialName("viewer_id") val viewerId: String = "",
    val balance: Int = 0,
    @SerialName("earned_total") val earnedTotal: Long = 0,
    @SerialName("redeemed_total") val redeemedTotal: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class LivePointsBalanceResponse(
    val balance: LivePointsBalance,
    val settings: LivePointsSettings,
)

@Serializable
data class LivePointReward(
    val id: String,
    @SerialName("live_id") val liveId: String,
    @SerialName("creator_id") val creatorId: String,
    val title: String,
    val description: String = "",
    val cost: Int,
    val active: Boolean = true,
    val stock: Int? = null,
    @SerialName("cooldown_seconds") val cooldownSeconds: Int = 0,
    val version: Long = 1,
)

@Serializable
data class LivePointRewardsResponse(val rewards: List<LivePointReward> = emptyList())

@Serializable
data class LivePointRedemption(
    val id: String,
    @SerialName("live_id") val liveId: String,
    @SerialName("reward_id") val rewardId: String,
    @SerialName("viewer_id") val viewerId: String,
    val cost: Int,
    val status: String,
    @SerialName("reward_title") val rewardTitle: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class LivePointRedemptionsResponse(val redemptions: List<LivePointRedemption> = emptyList())

@Serializable
data class LivePointRedemptionResponse(
    val redemption: LivePointRedemption,
    val balance: Int? = null,
    val duplicate: Boolean = false,
    val refunded: Boolean = false,
)

@Serializable
data class LivePointRewardResponse(val reward: LivePointReward)

@Serializable
data class LivePointsSettingsResponse(val settings: LivePointsSettings)
