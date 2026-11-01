package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

class ChallengeActionException(message: String) : IllegalStateException(message)


@Serializable
data class Challenge(
    val id: String,
    val title: String,
    val description: String,
    @SerialName("challenge_type") val challengeType: String, // 'drawing', 'camera', 'text'
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("creator_id") val creatorId: String? = null,
    val participantCount: Int = 0,
    val isCompleted: Boolean = false
)

@Serializable
data class ActiveChallenge(
    val id: String,
    @SerialName("challenge_id") val challengeId: String,
    val challenge: Challenge,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("voting_expires_at") val votingExpiresAt: String? = null,
    val status: String, // 'active', 'voting', 'completed'
    @SerialName("winner_ids") val winnerIds: List<String> = emptyList()
)

@Serializable
data class ActiveChallengeDto(
    @SerialName("active_id") val activeId: String,
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("voting_expires_at") val votingExpiresAt: String? = null,
    val status: String,
    @SerialName("winner_ids") val winnerIds: List<String> = emptyList()
)

@Serializable
data class ChallengeSubmission(
    val id: String,
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("active_challenge_id") val activeChallengeId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("user_data") val user: User? = null,
    @SerialName("content_url") val contentUrl: String? = null,
    @SerialName("text_content") val textContent: String? = null,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("voted_by_me") val votedByMe: Boolean = false,
    @SerialName("created_at") val createdAt: String
)
