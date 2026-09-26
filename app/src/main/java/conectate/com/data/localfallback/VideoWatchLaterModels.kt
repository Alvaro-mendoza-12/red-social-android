package conectate.com.data.localfallback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VideoWatchLaterItem(
    val videoId: String = "",
    val addedAt: String = "",
    val unavailable: Boolean = false,
    val title: String = "",
    val coverUrl: String = "",
    val creatorName: String = "",
    val durationSeconds: Double = 0.0,
)

@Serializable
data class VideoWatchLaterPage(
    val items: List<VideoWatchLaterItem> = emptyList(),
    val nextCursor: String? = null,
)
