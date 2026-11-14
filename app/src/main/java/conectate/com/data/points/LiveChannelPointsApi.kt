package conectate.com.data.points

import conectate.com.data.model.LivePointRedemptionResponse
import conectate.com.data.model.LivePointRedemptionsResponse
import conectate.com.data.model.LivePointRewardResponse
import conectate.com.data.model.LivePointRewardsResponse
import conectate.com.data.model.LivePointsBalanceResponse
import conectate.com.data.model.LivePointsHeartbeat
import conectate.com.data.model.LivePointsSession
import conectate.com.data.model.LivePointsSettingsResponse
import java.util.UUID

interface LiveChannelPointsApi {
    suspend fun startSession(token: String, liveId: String): Result<LivePointsSession>
    suspend fun heartbeat(token: String, liveId: String, sessionToken: String, visible: Boolean,
        foreground: Boolean): Result<LivePointsHeartbeat>
    suspend fun balance(token: String, liveId: String): Result<LivePointsBalanceResponse>
    suspend fun rewards(token: String, liveId: String): Result<LivePointRewardsResponse>
    suspend fun redemptions(token: String, liveId: String): Result<LivePointRedemptionsResponse>
    suspend fun redeem(token: String, liveId: String, rewardId: String,
        requestNonce: String = UUID.randomUUID().toString()): Result<LivePointRedemptionResponse>
    suspend fun updateSettings(token: String, liveId: String, enabled: Boolean, intervalSeconds: Int,
        pointsPerInterval: Int, dailyCap: Int): Result<LivePointsSettingsResponse>
    suspend fun createReward(token: String, liveId: String, title: String, description: String,
        cost: Int, stock: Int?, cooldownSeconds: Int): Result<LivePointRewardResponse>
    suspend fun updateRedemption(token: String, liveId: String, redemptionId: String,
        status: String): Result<LivePointRedemptionResponse>
}
