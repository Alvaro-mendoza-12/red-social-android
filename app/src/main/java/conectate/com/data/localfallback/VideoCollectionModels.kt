package conectate.com.data.localfallback

import kotlinx.serialization.Serializable

@Serializable
data class VideoCollection(
    val id: String = "",
    val name: String = "",
    val itemCount: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = "",
)

@Serializable
data class VideoCollectionCatalog(val items: List<VideoCollection> = emptyList())

@Serializable
data class VideoCollectionItem(
    val videoId: String = "",
    val sortOrder: Int = 0,
    val addedAt: String = "",
    val unavailable: Boolean = false,
    val title: String = "",
    val coverUrl: String = "",
    val creatorName: String = "",
)

@Serializable
data class VideoCollectionPage(
    val items: List<VideoCollectionItem> = emptyList(),
    val total: Int = 0,
    val nextOffset: Int? = null,
)

@Serializable
data class VideoCollectionNameRequest(val name: String)

@Serializable
data class VideoCollectionMoveRequest(val direction: String)
