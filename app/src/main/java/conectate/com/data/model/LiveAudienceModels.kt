package conectate.com.data.model

import kotlinx.serialization.Serializable

@Serializable
data class LiveAudienceState(
    val canModerate: Boolean = false,
    val hasMore: Boolean = false,
    val items: List<LiveAudienceItem> = emptyList(),
)

@Serializable
data class LiveAudienceItem(
    val id: String,
    val kind: String,
    val content: String,
    val options: List<String> = emptyList(),
    val status: String = "open",
    val answer: String = "",
    val highlighted: Boolean = false,
    val authorId: String = "",
    val authorName: String = "Participante",
    val closesAt: String? = null,
    val createdAt: String = "",
    val counts: List<Int> = emptyList(),
    val totalVotes: Int = 0,
    val myChoice: Int? = null,
)
