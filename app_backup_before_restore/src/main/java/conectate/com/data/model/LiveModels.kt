package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

enum class LiveSource {
    CAMERA,
    SCREEN
}

enum class LiveRole {
    HOST,
    VIEWER
}

enum class LiveQuality(val label: String, val width: Int, val height: Int) {
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080)
}

@Serializable
data class LiveStream(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    @SerialName("host_user_id") val hostUserId: String = "",
    @SerialName("host_name") val hostName: String = "",
    @SerialName("host_avatar_url") val hostAvatarUrl: String? = null,
    val source: String = "camera",
    @SerialName("viewer_count") val viewerCount: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class LiveComment(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("live_id") val liveId: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_name") val userName: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val content: String = "",
    @SerialName("created_at") val createdAt: String? = null
)

data class LiveReaction(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String = "❤️",
    val userId: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)

data class ActiveLiveSession(
    val stream: LiveStream,
    val role: LiveRole,
    val viewerCount: Int = 0
)
