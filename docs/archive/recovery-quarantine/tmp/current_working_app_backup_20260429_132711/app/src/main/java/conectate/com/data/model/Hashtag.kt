package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Hashtag(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val postCount: Int = 0,
    val videoCount: Int = 0,
    val trendingScore: Double = 0.0,
    val createdAt: String? = null,
    val updatedAt: String? = null
) {
    fun toDisplayName(): String {
        return "#${name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}"
    }
}

@Serializable
data class PostHashtag(
    val postId: String,
    val hashtagId: String,
    val position: Int = 0
)

@Serializable
data class VideoHashtag(
    val videoId: String,
    val hashtagId: String,
    val position: Int = 0
)

@Serializable
data class TrendingHashtag(
    val id: String,
    val name: String,
    val postCount: Int,
    val videoCount: Int,
    val trendingScore: Double
)

@Serializable
data class SearchResult(
    val contentType: String, // "post", "video", "hashtag"
    val contentId: String,
    val title: String?,
    val description: String?,
    val authorUsername: String?,
    val authorDisplayName: String?,
    val hashtags: List<String> = emptyList(),
    val rank: Float
)
