package conectate.com.data.repository

import conectate.com.data.model.*
import conectate.com.data.points.LiveChannelPointsApi
import conectate.com.data.points.LiveChannelPointsApiClient
import conectate.com.data.points.LivePointRedemptionStore
import conectate.com.data.points.LivePointRedemptionJournal
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ensureActive

@Singleton
class LiveChannelPointsRepository internal constructor(
    private val api: LiveChannelPointsApi,
    private val captureOwner: () -> AuthSessionLease,
    private val tokenForOwner: suspend (String) -> String?,
    private val redemptions: LivePointRedemptionStore,
) {
    @Inject internal constructor(auth: AuthService, api: LiveChannelPointsApiClient, journal: LivePointRedemptionJournal) : this(
        api, auth::captureAccountLease, auth::getAccessTokenForAccountAwait, journal,
    )

    internal suspend fun startSession(liveId: String, owner: AuthSessionLease = captureOwner()): Result<LivePointsSession> =
        withToken(owner) { api.startSession(it, liveId) }

    internal suspend fun heartbeat(liveId: String, sessionToken: String, visible: Boolean, foreground: Boolean,
        owner: AuthSessionLease = captureOwner()): Result<LivePointsHeartbeat> =
        withToken(owner) { api.heartbeat(it, liveId, sessionToken, visible, foreground) }

    internal suspend fun balance(liveId: String, owner: AuthSessionLease = captureOwner()): Result<LivePointsBalanceResponse> =
        withToken(owner) { api.balance(it, liveId) }

    internal suspend fun rewards(liveId: String, owner: AuthSessionLease = captureOwner()): Result<LivePointRewardsResponse> =
        withToken(owner) { api.rewards(it, liveId) }

    internal suspend fun redemptions(liveId: String, owner: AuthSessionLease = captureOwner()): Result<LivePointRedemptionsResponse> =
        withToken(owner) { api.redemptions(it, liveId) }

    internal suspend fun redeem(liveId: String, rewardId: String, owner: AuthSessionLease = captureOwner()): Result<LivePointRedemptionResponse> =
        try {
            Result.success(redemptions.execute(owner.accountId.orEmpty(), liveId, rewardId, owner::requireCurrent) { nonce ->
                withToken(owner) { api.redeem(it, liveId, rewardId, nonce) }.getOrThrow()
            })
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            owner.requireCurrent()
            Result.failure(error)
        }

    internal suspend fun updateSettings(liveId: String, enabled: Boolean, interval: Int, points: Int, cap: Int,
        owner: AuthSessionLease = captureOwner()): Result<LivePointsSettingsResponse> =
        withToken(owner) { api.updateSettings(it, liveId, enabled, interval, points, cap) }

    internal suspend fun createReward(liveId: String, title: String, description: String, cost: Int, stock: Int?, cooldown: Int,
        owner: AuthSessionLease = captureOwner()): Result<LivePointRewardResponse> =
        withToken(owner) { api.createReward(it, liveId, title, description, cost, stock, cooldown) }

    internal suspend fun updateRedemption(liveId: String, redemptionId: String, status: String,
        owner: AuthSessionLease = captureOwner()): Result<LivePointRedemptionResponse> =
        withToken(owner) { api.updateRedemption(it, liveId, redemptionId, status) }

    private suspend fun <T> withToken(owner: AuthSessionLease, request: suspend (String) -> Result<T>): Result<T> =
        notificationAccountCall(owner, tokenForOwner) { _, token -> request(token) }
}
