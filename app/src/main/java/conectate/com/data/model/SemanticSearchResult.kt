package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SemanticSearchResult(
    val id: String = "",
    val type: String = "post",
    val title: String = "",
    val excerpt: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    @SerialName("author_id") val authorId: String = "",
    @SerialName("author_name") val authorName: String = "Usuario",
    @SerialName("author_avatar_url") val authorAvatarUrl: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val score: Double = 0.0,
    val reason: String = "semantic_similarity"
)
