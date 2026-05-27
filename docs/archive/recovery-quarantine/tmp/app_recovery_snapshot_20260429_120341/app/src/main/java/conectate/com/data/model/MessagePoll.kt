package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MessagePollOptionState(
    @SerialName("poll_id") val pollId: String,
    @SerialName("option_id") val optionId: String,
    @SerialName("option_text") val optionText: String,
    @SerialName("display_order") val displayOrder: Int = 0,
    @SerialName("vote_count") val voteCount: Long = 0L,
    @SerialName("voted_by_me") val votedByMe: Boolean = false,
    @SerialName("total_votes") val totalVotes: Long = 0L,
    @SerialName("allows_multiple") val allowsMultiple: Boolean = false,
    @SerialName("is_closed") val isClosed: Boolean = false,
    @SerialName("question") val question: String = ""
)

@Serializable
data class MessageReadReceipt(
    @SerialName("user_id") val userId: String,
    @SerialName("username") val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("read_at") val readAt: String? = null
)

@Serializable
data class PollVoteUser(
    @SerialName("user_id") val userId: String,
    @SerialName("username") val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
)
