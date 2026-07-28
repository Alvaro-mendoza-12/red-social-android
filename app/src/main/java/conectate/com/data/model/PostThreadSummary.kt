package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PostThreadSummarySource(
    @SerialName("commentId") val commentId: String,
    @SerialName("authorName") val authorName: String,
    @SerialName("createdAt") val createdAt: String = "",
    @SerialName("deepLink") val deepLink: String = ""
)

@Serializable
data class PostThreadSummaryBullet(
    val text: String,
    val sources: List<PostThreadSummarySource> = emptyList()
)

@Serializable
data class PostThreadSummary(
    val kind: String = "extractive",
    val model: String = "novvin-thread-extractive-v1",
    val notice: String,
    @SerialName("generatedAt") val generatedAt: String = "",
    @SerialName("commentCount") val commentCount: Int = 0,
    val topics: List<String> = emptyList(),
    val bullets: List<PostThreadSummaryBullet> = emptyList()
)
