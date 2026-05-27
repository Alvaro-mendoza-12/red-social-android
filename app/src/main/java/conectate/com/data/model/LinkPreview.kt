package conectate.com.data.model

import kotlinx.serialization.Serializable

const val TYPE_WEBSITE = "website"
const val TYPE_VIDEO = "video"
const val TYPE_MUSIC = "music"
const val TYPE_ARTICLE = "article"
const val TYPE_SOCIAL = "social"
const val TYPE_CODE = "code"

@Serializable
data class LinkPreview(
    val url: String,
    val title: String,
    val description: String = "",
    val imageUrl: String? = null,
    val siteName: String = "",
    val type: String = TYPE_WEBSITE
)

// Extension functions for Link Preview
fun LinkPreview.getTypeIcon(): Int {
    return when (type) {
        TYPE_VIDEO -> android.R.drawable.ic_media_play
        TYPE_MUSIC -> android.R.drawable.ic_media_play
        TYPE_ARTICLE -> android.R.drawable.ic_menu_agenda
        TYPE_SOCIAL -> android.R.drawable.ic_menu_share
        TYPE_CODE -> android.R.drawable.ic_menu_preferences
        else -> android.R.drawable.ic_menu_set_as
    }
}

fun LinkPreview.getTypeColor(): Long {
    return when (type) {
        TYPE_VIDEO -> 0xFFFF0000 // Red for YouTube/TikTok
        TYPE_MUSIC -> 0xFF1DB954 // Green for Spotify
        TYPE_ARTICLE -> 0xFF4285F4 // Blue for news
        TYPE_SOCIAL -> 0xFF1DA1F2 // Twitter blue
        TYPE_CODE -> 0xFF6E7681 // Gray for GitHub
        else -> 0xFF757575 // Default gray
    }
}
