package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LiveDrawGuessGame(
    val id: String = "",
    @SerialName("live_id") val liveId: String = "",
    @SerialName("host_user_id") val hostUserId: String = "",
    val status: String = "lobby",
    @SerialName("max_players") val maxPlayers: Int = 8,
    @SerialName("total_rounds") val totalRounds: Int = 3,
    @SerialName("round_seconds") val roundSeconds: Int = 75,
    @SerialName("current_round_number") val currentRoundNumber: Int = 0,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null
)

@Serializable
data class LiveDrawGuessPlayer(
    @SerialName("user_id") val userId: String = "",
    @SerialName("seat_index") val seatIndex: Int = 0,
    val score: Long = 0L,
    val rank: Int? = null,
    val status: String = "invited"
)

@Serializable
data class LiveDrawGuessRound(
    val id: String = "",
    @SerialName("game_id") val gameId: String = "",
    @SerialName("round_number") val roundNumber: Int = 0,
    @SerialName("drawer_user_id") val drawerUserId: String = "",
    val status: String = "active",
    @SerialName("last_draw_sequence") val lastDrawSequence: Long = -1L,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null
)

@Serializable
data class LiveDrawGuessPrompt(
    val key: String = "",
    val label: String = "",
    val version: Int = 1
)

@Serializable
data class LiveDrawGuessState(
    val game: LiveDrawGuessGame = LiveDrawGuessGame(),
    val players: List<LiveDrawGuessPlayer> = emptyList(),
    val round: LiveDrawGuessRound? = null,
    @SerialName("private_prompt") val privatePrompt: LiveDrawGuessPrompt? = null,
    @SerialName("idempotent_replay") val idempotentReplay: Boolean = false
)

@Serializable
data class LiveDrawGuessGuessResult(
    val correct: Boolean = false,
    @SerialName("awarded_points") val awardedPoints: Int = 0,
    @SerialName("idempotent_replay") val idempotentReplay: Boolean = false,
    val state: LiveDrawGuessState = LiveDrawGuessState()
)

@Serializable
data class LiveDrawPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
    val deltaMs: Int = 0
)

@Serializable
data class LiveDrawPacket(
    val sequence: Long,
    val tool: String = "pen",
    val color: String = "#111111",
    val width: Float = 0.012f,
    val points: List<LiveDrawPoint>
)

@Serializable
data class LiveDrawPacketReceipt(
    val accepted: Boolean = false,
    val sequence: Long? = null,
    val reason: String? = null
)
