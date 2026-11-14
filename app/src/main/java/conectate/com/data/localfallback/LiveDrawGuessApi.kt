package conectate.com.data.localfallback

import conectate.com.data.model.LiveDrawGuessGuessResult
import conectate.com.data.model.LiveDrawGuessState
import conectate.com.data.model.LiveDrawPacket
import conectate.com.data.model.LiveDrawPacketReceipt
import java.util.UUID

/** Typed transport contract shared by the Ubuntu client and its repository. */
interface LiveDrawGuessApi {
    suspend fun loadCurrentLiveDrawGuess(accessToken: String, liveId: String): Result<LiveDrawGuessState?>
    suspend fun createLiveDrawGuess(accessToken: String, liveId: String, participantIds: List<String>,
        totalRounds: Int = 3, roundSeconds: Int = 75,
        idempotencyKey: String = UUID.randomUUID().toString()): Result<LiveDrawGuessState>
    suspend fun acceptLiveDrawGuess(accessToken: String, liveId: String, gameId: String,
        idempotencyKey: String = UUID.randomUUID().toString()): Result<LiveDrawGuessState>
    suspend fun startLiveDrawGuess(accessToken: String, liveId: String, gameId: String,
        idempotencyKey: String = UUID.randomUUID().toString()): Result<LiveDrawGuessState>
    suspend fun cancelLiveDrawGuess(accessToken: String, liveId: String, gameId: String,
        idempotencyKey: String = UUID.randomUUID().toString()): Result<LiveDrawGuessState>
    suspend fun advanceLiveDrawGuess(accessToken: String, liveId: String, gameId: String): Result<LiveDrawGuessState>
    suspend fun sendLiveDrawPacket(accessToken: String, liveId: String, gameId: String,
        packet: LiveDrawPacket): Result<LiveDrawPacketReceipt>
    suspend fun submitLiveDrawGuess(accessToken: String, liveId: String, gameId: String, guess: String,
        idempotencyKey: String = UUID.randomUUID().toString()): Result<LiveDrawGuessGuessResult>
}
