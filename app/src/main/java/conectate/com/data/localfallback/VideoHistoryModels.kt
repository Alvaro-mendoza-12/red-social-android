package conectate.com.data.localfallback

import kotlinx.serialization.Serializable

@Serializable
data class VideoHistoryItem(
    val videoId: String = "",
    val viewedAt: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val unavailable: Boolean = false,
    val title: String = "",
    val coverUrl: String = "",
    val creatorName: String = "",
)

@Serializable
data class VideoHistoryPage(
    val items: List<VideoHistoryItem> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class VideoHistoryProgress(val positionMs: Long, val durationMs: Long)
