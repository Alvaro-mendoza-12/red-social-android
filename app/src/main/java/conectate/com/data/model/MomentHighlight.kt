package conectate.com.data.model

data class MomentHighlight(
    val id: String,
    val userId: String,
    val title: String,
    val coverMediaUrl: String? = null,
    val sortOrder: Int = 0,
    val isPublic: Boolean = true,
    val itemCount: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

data class MomentHighlightItemPreview(
    val id: String,
    val highlightId: String,
    val momentId: String,
    val mediaUrl: String,
    val mediaType: String,
    val thumbnailUrl: String? = null,
    val caption: String? = null,
    val sortOrder: Int = 0,
    val createdAt: String? = null
)
