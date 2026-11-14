package conectate.com.data.repository

import conectate.com.data.localfallback.LiveDrawGuessApi
import conectate.com.data.model.*
import conectate.com.data.points.LivePointRedemptionStore
import conectate.com.data.points.LiveChannelPointsApi

internal class MemoryLivePointRedemptions : LivePointRedemptionStore {
    private val pending = mutableMapOf<Triple<String, String, String>, String>()
    override suspend fun execute(owner: String, liveId: String, rewardId: String, requireCurrent: () -> Unit,
        send: suspend (String) -> LivePointRedemptionResponse): LivePointRedemptionResponse {
        requireCurrent()
        val key = Triple(owner, liveId, rewardId)
        val nonce = pending.getOrPut(key) { java.util.UUID.randomUUID().toString() }
        val result = send(nonce)
        requireCurrent()
        if (pending[key] == nonce) pending.remove(key)
        return result
    }
}

internal open class RecordingLivePointsApi : LiveChannelPointsApi {
    val balanceTokens = mutableListOf<String>()
    val redemptionNonces = mutableListOf<String>()
    var readBalance: suspend (String) -> Result<LivePointsBalanceResponse> = { Result.success(balance(10)) }
    var redeemReward: suspend (String) -> Result<LivePointRedemptionResponse> = { Result.success(redemption()) }
    var readRedemptions: suspend () -> Result<LivePointRedemptionsResponse> = { Result.success(LivePointRedemptionsResponse()) }
    fun balance(value: Int) = LivePointsBalanceResponse(LivePointsBalance(balance = value), LivePointsSettings(enabled = true))
    fun redemption() = LivePointRedemptionResponse(LivePointRedemption("redemption", "live", "reward", "a", 3, "pending"), 7)
    override suspend fun startSession(token: String, liveId: String) = Result.success(LivePointsSession("session", "nonce", 300, 10, 2000))
    override suspend fun heartbeat(token: String, liveId: String, sessionToken: String, visible: Boolean, foreground: Boolean) = Result.success(LivePointsHeartbeat())
    override suspend fun balance(token: String, liveId: String): Result<LivePointsBalanceResponse> {
        balanceTokens += token
        return readBalance(token)
    }
    override suspend fun rewards(token: String, liveId: String) = Result.success(LivePointRewardsResponse())
    override suspend fun redemptions(token: String, liveId: String) = readRedemptions()
    override suspend fun redeem(token: String, liveId: String, rewardId: String, requestNonce: String): Result<LivePointRedemptionResponse> {
        redemptionNonces += requestNonce
        return redeemReward(requestNonce)
    }
    override suspend fun updateSettings(token: String, liveId: String, enabled: Boolean, intervalSeconds: Int, pointsPerInterval: Int, dailyCap: Int) = Result.success(LivePointsSettingsResponse(LivePointsSettings(enabled = enabled)))
    override suspend fun createReward(token: String, liveId: String, title: String, description: String, cost: Int, stock: Int?, cooldownSeconds: Int) = Result.success(LivePointRewardResponse(LivePointReward("reward", liveId, "host", title, cost = cost)))
    override suspend fun updateRedemption(token: String, liveId: String, redemptionId: String, status: String) = Result.success(redemption())
}

internal open class RecordingLiveDrawApi : LiveDrawGuessApi {
    val reads = mutableListOf<String>()
    val creations = mutableListOf<String>()
    var load: suspend (String) -> Result<LiveDrawGuessState?> = { Result.success(state("first")) }
    var create: suspend () -> Result<LiveDrawGuessState> = { Result.success(state("created")) }
    var sendDraw: suspend () -> Result<LiveDrawPacketReceipt> = { Result.success(LiveDrawPacketReceipt(true)) }
    fun state(round: String, prompt: String? = null) = LiveDrawGuessState(
        game = LiveDrawGuessGame(id = "game", liveId = "7245c3f0-3a6a-4d9d-93c1-e20236506111"),
        round = LiveDrawGuessRound(id = round, gameId = "game"),
        privatePrompt = prompt?.let { LiveDrawGuessPrompt("private", it) },
    )
    override suspend fun loadCurrentLiveDrawGuess(accessToken: String, liveId: String): Result<LiveDrawGuessState?> {
        reads += accessToken
        return load(accessToken)
    }
    override suspend fun createLiveDrawGuess(accessToken: String, liveId: String, participantIds: List<String>, totalRounds: Int, roundSeconds: Int, idempotencyKey: String): Result<LiveDrawGuessState> {
        creations += accessToken
        return create()
    }
    override suspend fun acceptLiveDrawGuess(accessToken: String, liveId: String, gameId: String, idempotencyKey: String) = Result.success(state("accepted"))
    override suspend fun startLiveDrawGuess(accessToken: String, liveId: String, gameId: String, idempotencyKey: String) = Result.success(state("started"))
    override suspend fun cancelLiveDrawGuess(accessToken: String, liveId: String, gameId: String, idempotencyKey: String) = Result.success(state("canceled"))
    override suspend fun advanceLiveDrawGuess(accessToken: String, liveId: String, gameId: String) = Result.success(state("next"))
    override suspend fun sendLiveDrawPacket(accessToken: String, liveId: String, gameId: String, packet: LiveDrawPacket) = sendDraw()
    override suspend fun submitLiveDrawGuess(accessToken: String, liveId: String, gameId: String, guess: String, idempotencyKey: String) = Result.success(LiveDrawGuessGuessResult(state = state("guessed")))
}
