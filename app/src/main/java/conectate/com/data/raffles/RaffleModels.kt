package conectate.com.data.raffles

import kotlinx.serialization.Serializable

@Serializable
data class RaffleParticipant(
    val id: String,
    val kind: String = "REGISTERED_MEMBER",
    val userId: String? = null,
    val name: String,
    val avatarUrl: String = "",
)

@Serializable
data class Raffle(
    val id: String, val kind: String, val roomId: String, val name: String,
    val description: String = "", val creatorId: String, val creatorName: String,
    val createdAt: String, val updatedAt: String, val status: String,
    val version: Int, val participantCount: Int, val roundCount: Int,
)

@Serializable
data class RaffleResult(val position: Int, val participant: RaffleParticipant, val selectedAt: String)

@Serializable
data class RaffleRound(
    val id: String, val number: Int, val status: String,
    val participants: List<RaffleParticipant>, val results: List<RaffleResult> = emptyList(),
    val initiatedBy: String, val automatic: Boolean = false, val nextDrawAt: String? = null,
    val startedAt: String, val finishedAt: String? = null,
)

@Serializable
data class RaffleDetail(
    val raffle: Raffle, val canManage: Boolean = false,
    val participants: List<RaffleParticipant>, val currentRound: RaffleRound? = null,
    val rounds: List<RaffleRound> = emptyList(), val nextRound: Int? = null,
)

@Serializable
data class RafflePage(val raffles: List<Raffle> = emptyList(), val canManage: Boolean = false,
    val nextCursor: String? = null, val canCreate: Boolean = canManage)

@Serializable
data class RaffleMemberPage(
    val participants: List<RaffleParticipant> = emptyList(), val canManage: Boolean = false,
    val total: Int = 0, val nextOffset: Int? = null, val canCreate: Boolean = canManage,
)

@Serializable
data class CreateRaffleRequest(
    val requestId: String, val name: String, val description: String = "",
    val memberIds: List<String>, val manualNames: List<String> = emptyList(),
    val start: Boolean = true, val automatic: Boolean = true,
)

@Serializable
data class RaffleCommandRequest(val requestId: String, val expectedVersion: Int, val automatic: Boolean = false)

/** Derived presentation state; only server results remove candidates. */
fun RaffleRound.remainingParticipants(): List<RaffleParticipant> {
    val drawn = results.mapTo(hashSetOf()) { it.participant.id }
    return participants.filter { it.id !in drawn }
}
