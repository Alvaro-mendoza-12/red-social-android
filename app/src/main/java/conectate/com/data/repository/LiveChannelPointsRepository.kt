package conectate.com.data.repository

import conectate.com.data.model.LivePointRedemptionResponse
import conectate.com.data.model.LivePointRedemptionsResponse
import conectate.com.data.model.LivePointRewardResponse
import conectate.com.data.model.LivePointRewardsResponse
import conectate.com.data.model.LivePointsBalanceResponse
import conectate.com.data.model.LivePointsHeartbeat
import conectate.com.data.model.LivePointsSession
import conectate.com.data.model.LivePointsSettingsResponse
import conectate.com.data.points.LiveChannelPointsApiClient
import conectate.com.data.supabase.AuthService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveChannelPointsRepository @Inject constructor(
    private val authService: AuthService,
    private val api: LiveChannelPointsApiClient,
) {
    suspend fun startSession(liveId: String): Result<LivePointsSession> = withToken { api.startSession(it, liveId) }
    suspend fun heartbeat(liveId: String, sessionToken: String, visible: Boolean, foreground: Boolean): Result<LivePointsHeartbeat> =
        withToken { api.heartbeat(it, liveId, sessionToken, visible, foreground) }
    suspend fun balance(liveId: String): Result<LivePointsBalanceResponse> = withToken { api.balance(it, liveId) }
    suspend fun rewards(liveId: String): Result<LivePointRewardsResponse> = withToken { api.rewards(it, liveId) }
    suspend fun redemptions(liveId: String): Result<LivePointRedemptionsResponse> = withToken { api.redemptions(it, liveId) }
    suspend fun redeem(liveId: String, rewardId: String): Result<LivePointRedemptionResponse> =
        withToken { api.redeem(it, liveId, rewardId) }
    suspend fun updateSettings(liveId: String, enabled: Boolean, interval: Int, points: Int, cap: Int): Result<LivePointsSettingsResponse> =
        withToken { api.updateSettings(it, liveId, enabled, interval, points, cap) }
    suspend fun createReward(liveId: String, title: String, description: String, cost: Int, stock: Int?, cooldown: Int): Result<LivePointRewardResponse> =
        withToken { api.createReward(it, liveId, title, description, cost, stock, cooldown) }
    suspend fun updateRedemption(liveId: String, redemptionId: String, status: String): Result<LivePointRedemptionResponse> =
        withToken { api.updateRedemption(it, liveId, redemptionId, status) }

    private suspend fun <T> withToken(block: suspend (String) -> Result<T>): Result<T> =
        authService.ensureValidAccessToken().fold(
            onSuccess = { block(it) },
            onFailure = { Result.failure(it) },
        )
}
